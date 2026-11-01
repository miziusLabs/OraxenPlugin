package io.th0rgal.oraxen.nms.handler.java21;

import io.th0rgal.oraxen.utils.SchedulerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Replays server lighting after clients apply the projected, non-emitting note block state. */
final class NativeBlockLight {
    private final Set<LightChunk> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean active = true;

    private record LightChunk(UUID world, int x, int z) {
    }

    void refresh(Block block) {
        Location location = block.getLocation();
        if (!Bukkit.isOwnedByCurrentRegion(location))
            throw new IllegalStateException("Native block lighting must update on the owning region");
        if (!active) return;
        World world = block.getWorld();
        ServerLevel level = ((CraftWorld) world).getHandle();
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        level.getChunkSource().getLightEngine().checkBlock(pos);
        int emission = level.getBlockState(pos).getLightEmission();
        if (emission == 0) return;
        int radius = emission - 1;
        for (int chunkX = (pos.getX() - radius) >> 4; chunkX <= (pos.getX() + radius) >> 4; chunkX++) {
            for (int chunkZ = (pos.getZ() - radius) >> 4; chunkZ <= (pos.getZ() + radius) >> 4; chunkZ++)
                queue(world, chunkX, chunkZ, block.getY());
        }
    }

    private void queue(World world, int chunkX, int chunkZ, int y) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) return;
        LightChunk key = new LightChunk(world.getUID(), chunkX, chunkZ);
        if (!pending.add(key)) return;
        Location location = new Location(world, chunkX << 4, y, chunkZ << 4);
        try {
            SchedulerUtil.runAtLocationLater(location, 2L, () -> {
                try {
                    if (!active || !world.isChunkLoaded(chunkX, chunkZ)) return;
                    ServerLevel level = ((CraftWorld) world).getHandle();
                    var packet = new ClientboundLightUpdatePacket(new ChunkPos(chunkX, chunkZ),
                            level.getChunkSource().getLightEngine(), null, null);
                    long chunkKey = ((long) chunkZ << 32) | (chunkX & 0xffffffffL);
                    for (var player : world.getPlayersSeeingChunk(chunkX, chunkZ)) {
                        SchedulerUtil.runOnOwningThread(player, () -> {
                            if (active && player.isOnline() && player.getWorld().getUID().equals(key.world())
                                    && player.isChunkSent(chunkKey))
                                ((CraftPlayer) player).getHandle().connection.send(packet);
                        });
                    }
                } finally {
                    pending.remove(key);
                }
            });
        } catch (RuntimeException exception) {
            pending.remove(key);
            throw exception;
        }
    }

    void shutdown() {
        active = false;
        pending.clear();
    }
}
