package io.th0rgal.oraxen.nms.handler.shared;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class FurnitureBedPackets {

    private static final EntityDataAccessor<Boolean> noGravity = accessor(Entity.class, "DATA_NO_GRAVITY", 5, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<BlockPos>> sleepingPos =
            accessor(LivingEntity.class, "SLEEPING_POS_ID", 14, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final int teleportId = Integer.MIN_VALUE;
    private static final Field moveEntityId = field(ClientboundMoveEntityPacket.class);
    private static final Field rotateHeadEntityId = field(ClientboundRotateHeadPacket.class);

    private record Bed(ServerPlayer sleeper, UUID worldId, Vec3 head, float headYaw, BlockPos bedPos,
                       BlockState bedState, BlockState originalBedState, BlockPos headPos, boolean headBarrier,
                       Set<UUID> viewers) {

        private BlockState clientState(BlockPos pos, BlockState state, boolean self) {
            if (pos.equals(bedPos)) return bedState;
            return self && pos.equals(headPos) && state.is(Blocks.BARRIER) ? Blocks.AIR.defaultBlockState() : state;
        }
    }

    private final Map<Integer, Bed> beds = new ConcurrentHashMap<>();

    boolean isEmpty() {
        return beds.isEmpty();
    }

    void clear() {
        for (Integer entityId : new ArrayList<>(beds.keySet())) forget(entityId);
    }

    void forget(int playerEntityId) {
        Bed bed = beds.remove(playerEntityId);
        if (bed == null || bed.viewers().isEmpty()) return;
        World world = Bukkit.getWorld(bed.worldId());
        if (world == null) return;
        Location bedLocation = new Location(world, bed.bedPos().getX(), bed.bedPos().getY(), bed.bedPos().getZ());
        restoreBedBlocksOnOwningRegion(bed, world, bedLocation, new HashSet<>(bed.viewers()), null);
    }

    boolean show(Player player, Location head, float feetYaw) {
        ServerPlayer sleeper = ((CraftPlayer) player).getHandle();
        World world = player.getWorld();
        BlockPos bedPos = findBedPos(world, head, sleeper.getId());
        org.bukkit.block.data.type.Bed bedData = (org.bukkit.block.data.type.Bed) Material.RED_BED.createBlockData();
        bedData.setFacing(BlockFace.valueOf(Direction.fromYRot(feetYaw).getOpposite().name()));
        bedData.setPart(org.bukkit.block.data.type.Bed.Part.HEAD);
        BlockState bedState = ((CraftBlockData) bedData).getState();
        BlockState originalBedState = ((CraftBlockData) world.getBlockData(blockLocation(player, bedPos))).getState();
        Vec3 headPosition = new Vec3(head.getX(), head.getY(), head.getZ());
        BlockPos headPos = BlockPos.containing(headPosition);
        boolean headBarrier = world.getBlockAt(head).getType() == Material.BARRIER;
        Bed bed = new Bed(sleeper, world.getUID(), headPosition, sleeper.yBodyRot, bedPos,
                bedState, originalBedState, headPos, headBarrier, ConcurrentHashMap.newKeySet());
        beds.put(sleeper.getId(), bed);
        player.sendBlockChange(blockLocation(player, bedPos), bedData);
        if (headBarrier)
            player.sendBlockChange(head, Material.AIR.createBlockData());
        sleeper.connection.send(bundle(new ClientboundSetEntityDataPacket(sleeper.getId(), List.of(
                        SynchedEntityData.DataValue.create(noGravity, true),
                        SynchedEntityData.DataValue.create(sleepingPos, Optional.of(bedPos)))),
                new ClientboundPlayerPositionPacket(teleportId,
                        new PositionMoveRotation(headPosition, Vec3.ZERO, 0f, 0f), Relative.ROTATION)));
        // The carrier entity is hidden from clients, so tracking viewers need an explicit sync to the bed position and orientation.
        // Send the teleport after sleepingPos because LivingEntity#onSyncedDataUpdated snaps the entity to bedPos on the client.
        Packet<? super ClientGamePacketListener> viewerSync = viewerSyncPacket(bed);
        UUID sleeperId = player.getUniqueId();
        for (Player viewer : player.getTrackedBy()) {
            if (viewer.getUniqueId().equals(sleeperId)) continue;
            bed.viewers().add(viewer.getUniqueId());
            runOnViewerThread(viewer, bed.worldId(), () -> {
                if (beds.get(sleeper.getId()) == bed)
                    ((CraftPlayer) viewer).getHandle().connection.send(viewerSync);
            });
        }
        return true;
    }

    private static Packet<? super ClientGamePacketListener> viewerSyncPacket(Bed bed) {
        ServerPlayer sleeper = bed.sleeper();
        return bundle(
                new ClientboundBlockUpdatePacket(bed.bedPos(), bed.bedState()),
                new ClientboundSetEntityDataPacket(sleeper.getId(), List.of(
                        SynchedEntityData.DataValue.create(sleepingPos, Optional.of(bed.bedPos())))),
                new ClientboundTeleportEntityPacket(sleeper.getId(), position(bed), Set.of(), sleeper.onGround()),
                new ClientboundRotateHeadPacket(sleeper, Mth.packDegrees(bed.headYaw())));
    }

    void hide(Player player) {
        ServerPlayer sleeper = ((CraftPlayer) player).getHandle();
        Bed bed = beds.remove(sleeper.getId());
        if (bed == null) return;
        sleeper.connection.send(new ClientboundSetEntityDataPacket(sleeper.getId(), List.of(
                SynchedEntityData.DataValue.create(noGravity, sleeper.isNoGravity()),
                SynchedEntityData.DataValue.create(sleepingPos, sleeper.getSleepingPos()))));

        Set<UUID> viewers = new HashSet<>(bed.viewers());
        for (Player tracker : player.getTrackedBy()) viewers.add(tracker.getUniqueId());
        viewers.remove(player.getUniqueId());

        Packet<? super ClientGamePacketListener> sleepingPosReset = new ClientboundSetEntityDataPacket(sleeper.getId(), List.of(
                SynchedEntityData.DataValue.create(sleepingPos, sleeper.getSleepingPos())));
        Packet<? super ClientGamePacketListener> teleportReset = new ClientboundTeleportEntityPacket(sleeper.getId(),
                new PositionMoveRotation(sleeper.position(), Vec3.ZERO, sleeper.getYRot(), sleeper.getXRot()),
                Set.of(), sleeper.onGround());

        World world = Bukkit.getWorld(bed.worldId());
        if (world != null) {
            Location bedLocation = new Location(world, bed.bedPos().getX(), bed.bedPos().getY(), bed.bedPos().getZ());
            if (Bukkit.isOwnedByCurrentRegion(bedLocation)) {
                BlockState currentBedState = ((CraftBlockData) world.getBlockData(bedLocation)).getState();
                if (player.getWorld().getUID().equals(bed.worldId())) {
                    if (!isBedPosUsed(bed.worldId(), bed.bedPos(), -1))
                        sleeper.connection.send(new ClientboundBlockUpdatePacket(bed.bedPos(), currentBedState));
                    if (bed.headBarrier())
                        restoreBlock(player, bed.headPos());
                }
                sendViewerHide(bed, viewers, currentBedState, sleepingPosReset, teleportReset);
                return;
            }
            sendViewerEntityReset(bed, viewers, sleepingPosReset, teleportReset);
            restoreBedBlocksOnOwningRegion(bed, world, bedLocation, viewers, player);
            return;
        }
        sendViewerEntityReset(bed, viewers, sleepingPosReset, teleportReset);
    }

    private void sendViewerHide(Bed bed, Set<UUID> viewerIds, BlockState currentBedState,
                                Packet<? super ClientGamePacketListener> sleepingPosReset,
                                Packet<? super ClientGamePacketListener> teleportReset) {
        Packet<? super ClientGamePacketListener> blockRestore = new ClientboundBlockUpdatePacket(bed.bedPos(), currentBedState);
        int sleeperId = bed.sleeper().getId();
        for (UUID viewerId : viewerIds) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null) continue;
            runOnViewerThread(viewer, bed.worldId(), () -> {
                boolean restoreBlock = !isBedPosUsed(bed.worldId(), bed.bedPos(), -1);
                boolean resetEntity = !beds.containsKey(sleeperId);
                if (restoreBlock && resetEntity)
                    ((CraftPlayer) viewer).getHandle().connection.send(bundle(blockRestore, sleepingPosReset, teleportReset));
                else if (restoreBlock)
                    ((CraftPlayer) viewer).getHandle().connection.send(blockRestore);
                else if (resetEntity)
                    ((CraftPlayer) viewer).getHandle().connection.send(bundle(sleepingPosReset, teleportReset));
            });
        }
    }

    private void sendViewerEntityReset(Bed bed, Set<UUID> viewerIds,
                                       Packet<? super ClientGamePacketListener> sleepingPosReset,
                                       Packet<? super ClientGamePacketListener> teleportReset) {
        int sleeperId = bed.sleeper().getId();
        Packet<? super ClientGamePacketListener> entityReset = bundle(sleepingPosReset, teleportReset);
        for (UUID viewerId : viewerIds) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null) continue;
            runOnViewerThread(viewer, bed.worldId(), () -> {
                if (!beds.containsKey(sleeperId))
                    ((CraftPlayer) viewer).getHandle().connection.send(entityReset);
            });
        }
    }

    private void restoreBedBlocksOnOwningRegion(Bed bed, World world, Location bedLocation,
                                                Set<UUID> viewerIds, Player sleeper) {
        Runnable readAndSend = () -> {
            if (!world.isChunkLoaded(bed.bedPos().getX() >> 4, bed.bedPos().getZ() >> 4)) return;
            BlockState currentBedState = ((CraftBlockData) world.getBlockData(bedLocation)).getState();
            Packet<? super ClientGamePacketListener> bedRestore = new ClientboundBlockUpdatePacket(bed.bedPos(), currentBedState);
            if (sleeper != null) {
                BlockState currentHeadState = bed.headBarrier()
                        ? ((CraftBlockData) world.getBlockData(new Location(world, bed.headPos().getX(), bed.headPos().getY(), bed.headPos().getZ()))).getState()
                        : null;
                runOnViewerThread(sleeper, bed.worldId(), () -> {
                    boolean restoreBed = !isBedPosUsed(bed.worldId(), bed.bedPos(), -1);
                    if (restoreBed && currentHeadState != null)
                        ((CraftPlayer) sleeper).getHandle().connection.send(bundle(
                                bedRestore, new ClientboundBlockUpdatePacket(bed.headPos(), currentHeadState)));
                    else if (restoreBed)
                        ((CraftPlayer) sleeper).getHandle().connection.send(bedRestore);
                    else if (currentHeadState != null)
                        ((CraftPlayer) sleeper).getHandle().connection.send(
                                new ClientboundBlockUpdatePacket(bed.headPos(), currentHeadState));
                });
            }
            for (UUID viewerId : viewerIds) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null) continue;
                runOnViewerThread(viewer, bed.worldId(), () -> {
                    if (!isBedPosUsed(bed.worldId(), bed.bedPos(), -1))
                        ((CraftPlayer) viewer).getHandle().connection.send(bedRestore);
                });
            }
        };
        if (Bukkit.isOwnedByCurrentRegion(bedLocation)) {
            readAndSend.run();
        } else if (isPluginEnabled()) {
            SchedulerUtil.runAtLocation(bedLocation, readAndSend);
        } else {
            Packet<? super ClientGamePacketListener> fallback = new ClientboundBlockUpdatePacket(bed.bedPos(), bed.originalBedState());
            for (UUID viewerId : viewerIds) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer == null || !Bukkit.isOwnedByCurrentRegion(viewer)) continue;
                if (viewer.isOnline() && viewer.getWorld().getUID().equals(bed.worldId())
                        && !isBedPosUsed(bed.worldId(), bed.bedPos(), -1))
                    ((CraftPlayer) viewer).getHandle().connection.send(fallback);
            }
        }
    }

    private static void runOnViewerThread(Player viewer, UUID worldId, Runnable action) {
        Runnable guarded = () -> {
            if (!viewer.isOnline() || !viewer.getWorld().getUID().equals(worldId)) return;
            action.run();
        };
        if (Bukkit.isOwnedByCurrentRegion(viewer)) {
            guarded.run();
            return;
        }
        if (!isPluginEnabled()) return;
        SchedulerUtil.runForEntity(viewer, guarded);
    }

    private static boolean isPluginEnabled() {
        OraxenPlugin plugin = OraxenPlugin.get();
        return plugin != null && plugin.isEnabled();
    }

    private BlockPos findBedPos(World world, Location head, int sleeperId) {
        int min = world.getMinHeight();
        int top = world.getMaxHeight() - 1;
        int bedY = head.getBlockY() - min > top - head.getBlockY() ? min : top;
        int step = bedY == min ? 1 : -1;
        UUID worldId = world.getUID();
        BlockPos pos = new BlockPos(head.getBlockX(), bedY, head.getBlockZ());
        while (isBedPosUsed(worldId, pos, sleeperId)) {
            bedY += step;
            if (bedY < min || bedY > top) break;
            pos = new BlockPos(head.getBlockX(), bedY, head.getBlockZ());
        }
        return pos;
    }

    private boolean isBedPosUsed(UUID worldId, BlockPos pos, int sleeperId) {
        for (Bed existing : beds.values()) {
            if (existing.sleeper().getId() != sleeperId
                    && existing.worldId().equals(worldId)
                    && existing.bedPos().equals(pos)) return true;
        }
        return false;
    }

    private static void restoreBlock(Player player, BlockPos pos) {
        Location location = blockLocation(player, pos);
        player.sendBlockChange(location, player.getWorld().getBlockData(location));
    }

    private static Location blockLocation(Player player, BlockPos pos) {
        return new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ());
    }

    void onIncoming(Object packet, ServerPlayer sender) {
        if (!(packet instanceof ServerboundPlayerCommandPacket command) || sender == null
                || command.getAction() != ServerboundPlayerCommandPacket.Action.STOP_SLEEPING
                || !beds.containsKey(sender.getId())) return;
        org.bukkit.entity.Player player = sender.getBukkitEntity();
        SchedulerUtil.runForEntity(player, player::leaveVehicle, null);
    }

    Object transform(Object packet, ServerPlayer receiver) throws IllegalAccessException {
        if (packet instanceof ClientboundSetEntityDataPacket data) return transformData(data, receiver);
        if (packet instanceof ClientboundBlockUpdatePacket update) return transformBlockUpdate(update, receiver);
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) return transformChunk(chunk, receiver);
        if (packet instanceof ClientboundAddEntityPacket spawn) return transformSpawn(spawn, receiver);
        if (packet instanceof ClientboundMoveEntityPacket move && moveEntityId != null) {
            Bed bed = viewedBed(moveEntityId.getInt(move), receiver);
            if (bed != null) return new ClientboundMoveEntityPacket.Rot(moveEntityId.getInt(move),
                    Mth.packDegrees(bed.headYaw()), (byte) 0, move.isOnGround());
        }
        if (packet instanceof ClientboundRotateHeadPacket rotateHead && rotateHeadEntityId != null) {
            Bed bed = viewedBed(rotateHeadEntityId.getInt(rotateHead), receiver);
            if (bed != null) return new ClientboundRotateHeadPacket(bed.sleeper(), Mth.packDegrees(bed.headYaw()));
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            Bed bed = viewedBed(teleport.id(), receiver);
            if (bed != null) return new ClientboundTeleportEntityPacket(teleport.id(), position(bed), Set.of(), teleport.onGround());
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            Bed bed = viewedBed(sync.id(), receiver);
            if (bed != null) return new ClientboundTeleportEntityPacket(sync.id(), position(bed), Set.of(), sync.onGround());
        }
        return packet;
    }

    private Bed viewedBed(int entityId, ServerPlayer receiver) {
        Bed bed = beds.get(entityId);
        return bed == null || bed.sleeper() == receiver
                || !bed.worldId().equals(receiver.level().getWorld().getUID()) ? null : bed;
    }

    private static PositionMoveRotation position(Bed bed) {
        return new PositionMoveRotation(bed.head(), Vec3.ZERO, bed.headYaw(), 0f);
    }

    private Object transformData(ClientboundSetEntityDataPacket packet, ServerPlayer receiver) {
        Bed bed = beds.get(packet.id());
        if (bed == null) return packet;
        boolean self = bed.sleeper() == receiver;
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(packet.packedItems().size());
        boolean changed = false;
        boolean sleepingPosChanged = false;
        for (SynchedEntityData.DataValue<?> value : packet.packedItems()) {
            if (self && value.id() == noGravity.id()) {
                values.add(SynchedEntityData.DataValue.create(noGravity, true));
                changed = true;
            } else if (value.id() == sleepingPos.id()) {
                Optional<BlockPos> target = Optional.of(bed.bedPos());
                if (!target.equals(value.value())) {
                    values.add(SynchedEntityData.DataValue.create(sleepingPos, target));
                    changed = true;
                    sleepingPosChanged = true;
                } else values.add(value);
            } else values.add(value);
        }
        if (!changed) return packet;
        ClientboundSetEntityDataPacket updated = new ClientboundSetEntityDataPacket(packet.id(), values);
        if (!sleepingPosChanged) return updated;
        if (self) return bundle(updated, new ClientboundPlayerPositionPacket(teleportId,
                new PositionMoveRotation(bed.head(), Vec3.ZERO, 0f, 0f), Relative.ROTATION));
        return bundle(updated, new ClientboundTeleportEntityPacket(packet.id(), position(bed), Set.of(), bed.sleeper().onGround()));
    }

    private Object transformBlockUpdate(ClientboundBlockUpdatePacket update, ServerPlayer receiver) {
        BlockPos pos = update.getPos();
        BlockState state = update.getBlockState();
        UUID worldId = receiver.level().getWorld().getUID();
        Bed selfBed = beds.get(receiver.getId());
        if (selfBed != null && selfBed.worldId().equals(worldId)) {
            BlockState adjusted = selfBed.clientState(pos, state, true);
            if (adjusted != state) return new ClientboundBlockUpdatePacket(pos, adjusted);
        }
        UUID receiverId = receiver.getUUID();
        for (Bed bed : beds.values()) {
            if (bed.sleeper() == receiver || !bed.worldId().equals(worldId) || !bed.viewers().contains(receiverId)) continue;
            BlockState adjusted = bed.clientState(pos, state, false);
            if (adjusted != state) return new ClientboundBlockUpdatePacket(pos, adjusted);
        }
        return update;
    }

    // Chunk sends can arrive after entity spawn and overwrite the fake bed block with real chunk data.
    private Object transformChunk(ClientboundLevelChunkWithLightPacket chunk, ServerPlayer receiver) {
        List<Packet<? super ClientGamePacketListener>> packets = null;
        UUID worldId = receiver.level().getWorld().getUID();
        UUID receiverId = receiver.getUUID();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        for (Bed bed : beds.values()) {
            if (!bed.worldId().equals(worldId)) continue;
            boolean self = bed.sleeper() == receiver;
            boolean bedInChunk = (bed.bedPos().getX() >> 4) == chunkX && (bed.bedPos().getZ() >> 4) == chunkZ;
            boolean headInChunk = self && bed.headBarrier()
                    && (bed.headPos().getX() >> 4) == chunkX && (bed.headPos().getZ() >> 4) == chunkZ;
            if (!bedInChunk && !headInChunk) continue;
            if (packets == null) packets = new ArrayList<>(List.of(chunk));
            if (bedInChunk) {
                if (!self) bed.viewers().add(receiverId);
                packets.add(new ClientboundBlockUpdatePacket(bed.bedPos(), bed.bedState()));
            }
            if (headInChunk) packets.add(new ClientboundBlockUpdatePacket(bed.headPos(), Blocks.AIR.defaultBlockState()));
        }
        return packets == null ? chunk : new ClientboundBundlePacket(packets);
    }

    private Object transformSpawn(ClientboundAddEntityPacket spawn, ServerPlayer receiver) {
        Bed bed = viewedBed(spawn.getId(), receiver);
        if (bed == null) return spawn;
        bed.viewers().add(receiver.getUUID());
        Vec3 head = bed.head();
        // LivingEntity#baseTick snaps a newly spawned entity to sleepingPos on its first tick, after this bundle runs.
        // Applying sleepingPos + teleport once firstTick has cleared avoids leaving the sleeper at bedPos.
        if (isPluginEnabled()) {
            Player viewer = receiver.getBukkitEntity();
            SchedulerUtil.runForEntityLater(viewer, 2L, () -> {
                if (beds.get(spawn.getId()) != bed || !viewer.isOnline() || !viewer.getWorld().getUID().equals(bed.worldId())) return;
                ((CraftPlayer) viewer).getHandle().connection.send(viewerSyncPacket(bed));
            });
        }
        return new ClientboundBundlePacket(List.of(
                new ClientboundBlockUpdatePacket(bed.bedPos(), bed.bedState()),
                new ClientboundAddEntityPacket(spawn.getId(), spawn.getUUID(), head.x, head.y, head.z, 0f,
                        bed.headYaw(), spawn.getType(), spawn.getData(), Vec3.ZERO, bed.headYaw())));
    }

    @SafeVarargs
    private static ClientboundBundlePacket bundle(Packet<? super ClientGamePacketListener>... packets) {
        return new ClientboundBundlePacket(List.of(packets));
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityDataAccessor<T> accessor(Class<?> owner, String name, int fallbackId, EntityDataSerializer<T> serializer) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return (EntityDataAccessor<T>) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return new EntityDataAccessor<>(fallbackId, serializer);
        }
    }

    private static Field field(Class<?> owner) {
        try {
            Field field = owner.getDeclaredField("entityId");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
