package io.th0rgal.oraxen.nms.handler.java21;

import io.netty.buffer.Unpooled;
import io.th0rgal.oraxen.utils.VersionUtil;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.Optional;

/** Projects packet snapshots without accessing live chunks from a Netty thread. */
final class NativeBlockPackets {
    private static final boolean fluidSectionCount = VersionUtil.isModernVersionNamespace();
    private static final boolean fixedPaletteLength = VersionUtil.atOrAbove("1.21.5");
    private NativeBlockPackets() {
    }

    static Object transform(Object packet) throws ReflectiveOperationException {
        if (!NativeCustomBlocks.enabled()) return packet;
        // The client sees the reserved harp state. Real playback particles come from NotePlayEvent.
        if (packet instanceof ClientboundBlockEventPacket event && value(event, "block") == Blocks.NOTE_BLOCK) return null;
        if (packet instanceof ClientboundSetEntityDataPacket metadata) {
            var values = new ArrayList<SynchedEntityData.DataValue<?>>();
            boolean changed = false;
            for (var entry : metadata.packedItems()) {
                Object projected = projectMetadata(entry.value());
                changed |= projected != entry.value();
                values.add(projected == entry.value() ? entry : metadataValue(entry, projected));
            }
            return changed ? new ClientboundSetEntityDataPacket(metadata.id(), values) : packet;
        }
        if (packet instanceof ClientboundAddEntityPacket entity && ((EntityType<?>) value(entity, "type")).getDescriptionId().equals("entity.minecraft.falling_block")) {
            int data = (int) value(entity, "data");
            int projected = NativeCustomBlocks.project(data);
            if (data == projected) return packet;
            ClientboundAddEntityPacket copy = copy(entity, ClientboundAddEntityPacket.STREAM_CODEC);
            NativeCustomBlocks.field(ClientboundAddEntityPacket.class, "data").setInt(copy, projected);
            return copy;
        }
        if (packet instanceof ClientboundBlockEntityDataPacket blockEntity) {
            CompoundTag tag = (CompoundTag) value(blockEntity, "tag");
            CompoundTag projected = projectTag(tag);
            return tag == projected ? packet : new ClientboundBlockEntityDataPacket(
                    (BlockPos) value(blockEntity, "pos"), (BlockEntityType<?>) value(blockEntity, "type"), projected);
        }
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            BlockState state = (BlockState) value(update, "blockState");
            BlockState projected = NativeCustomBlocks.project(state);
            return projected == state ? packet : new ClientboundBlockUpdatePacket((BlockPos) value(update, "pos"), projected);
        }
        if (packet instanceof ClientboundSectionBlocksUpdatePacket update) {
            var states = new Short2ObjectOpenHashMap<BlockState>();
            boolean[] changed = {false};
            update.runUpdates((pos, state) -> {
                BlockState projected = NativeCustomBlocks.project(state);
                changed[0] |= projected != state;
                states.put(SectionPos.sectionRelativePos(pos), projected);
            });
            return changed[0] ? new ClientboundSectionBlocksUpdatePacket(
                    (SectionPos) NativeCustomBlocks.field(ClientboundSectionBlocksUpdatePacket.class, "sectionPos").get(update), states) : packet;
        }
        if (packet instanceof ClientboundLevelEventPacket event && (int) value(event, "type") == 2001) {
            int data = (int) value(event, "data");
            int projected = NativeCustomBlocks.project(data);
            return projected == data ? packet : new ClientboundLevelEventPacket(
                    2001, (BlockPos) value(event, "pos"), projected, (boolean) value(event, "globalEvent"));
        }
        if (packet instanceof ClientboundLevelParticlesPacket particles
                && value(particles, "particle") instanceof BlockParticleOption block) {
            BlockState projected = NativeCustomBlocks.project(block.getState());
            if (projected == block.getState()) return packet;
            BlockParticleOption particle = new BlockParticleOption(block.getType(), projected);
            if (particles.getClass().isRecord()) return replaceRecordComponent(particles, "particle", particle);
            return new ClientboundLevelParticlesPacket(particle,
                    (boolean) value(particles, "overrideLimiter"), (boolean) value(particles, "alwaysShow"),
                    (double) value(particles, "x"), (double) value(particles, "y"), (double) value(particles, "z"),
                    (float) value(particles, "xDist"), (float) value(particles, "yDist"), (float) value(particles, "zDist"),
                    (float) value(particles, "maxSpeed"), (int) value(particles, "count"));
        }
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) return transformChunk(chunk);
        return packet;
    }

    private static Object value(Object packet, String name) throws ReflectiveOperationException {
        return NativeCustomBlocks.field(packet.getClass(), name).get(packet);
    }

    private static Object projectMetadata(Object value) {
        if (value instanceof BlockState state) return NativeCustomBlocks.project(state);
        if (value instanceof Optional<?> optional && optional.orElse(null) instanceof BlockState state) {
            BlockState projected = NativeCustomBlocks.project(state);
            return state == projected ? value : Optional.of(projected);
        }
        if (value instanceof BlockParticleOption particle) {
            BlockState projected = NativeCustomBlocks.project(particle.getState());
            return projected == particle.getState() ? value : new BlockParticleOption(particle.getType(), projected);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static SynchedEntityData.DataValue<?> metadataValue(SynchedEntityData.DataValue<?> entry, Object value) {
        return new SynchedEntityData.DataValue<>(entry.id(), (EntityDataSerializer<Object>) entry.serializer(), value);
    }

    private static CompoundTag projectTag(CompoundTag tag) {
        if (tag == null || !(tag.get("blockState") instanceof CompoundTag stateTag)) return tag;
        BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, stateTag);
        BlockState projected = NativeCustomBlocks.project(state);
        if (state == projected) return tag;
        CompoundTag copy = tag.copy();
        copy.put("blockState", NbtUtils.writeBlockState(projected));
        return copy;
    }

    private static <T> T copy(T packet, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), MinecraftServer.getServer().registryAccess());
        try {
            codec.encode(buffer, packet);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static Object replaceRecordComponent(Object record, String name, Object replacement) throws ReflectiveOperationException {
        var components = record.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            var accessor = components[i].getAccessor();
            accessor.setAccessible(true);
            values[i] = components[i].getName().equals(name) ? replacement : accessor.invoke(record);
        }
        var constructor = record.getClass().getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static Object transformChunk(ClientboundLevelChunkWithLightPacket original) throws ReflectiveOperationException {
        ClientboundLevelChunkPacketData chunkData = (ClientboundLevelChunkPacketData) value(original, "chunkData");
        FriendlyByteBuf input = chunkData.getReadBuffer();
        FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer(input.readableBytes()));
        try {
            while (input.isReadable()) {
                output.writeShort(input.readShort());
                if (fluidSectionCount) output.writeShort(input.readShort());
                palette(input, output, true);
                palette(input, output, false);
            }
            byte[] projected = new byte[output.readableBytes()];
            output.getBytes(0, projected);
            // Chunk packets may be shared by several connections. Copy before replacing their byte buffer.
            ClientboundLevelChunkWithLightPacket copy = copy(original, ClientboundLevelChunkWithLightPacket.STREAM_CODEC);
            Object dataCopy = value(copy, "chunkData");
            NativeCustomBlocks.field(ClientboundLevelChunkPacketData.class, "buffer").set(dataCopy, projected);
            var entities = new ArrayList<>();
            for (Object blockEntity : (Iterable<?>) value(dataCopy, "blockEntitiesData")) {
                Object originalTag = value(blockEntity, "tag");
                CompoundTag tag = originalTag instanceof Optional<?> optional
                        ? (CompoundTag) optional.orElse(null) : (CompoundTag) originalTag;
                CompoundTag tagCopy = projectTag(tag);
                if (tagCopy != tag) {
                    if (blockEntity.getClass().isRecord())
                        blockEntity = replaceRecordComponent(blockEntity, "tag", Optional.of(tagCopy));
                    else NativeCustomBlocks.field(blockEntity.getClass(), "tag").set(blockEntity, tagCopy);
                }
                entities.add(blockEntity);
            }
            NativeCustomBlocks.field(ClientboundLevelChunkPacketData.class, "blockEntitiesData").set(dataCopy, entities);
            return copy;
        } finally {
            input.release();
            output.release();
        }
    }

    private static void palette(FriendlyByteBuf input, FriendlyByteBuf output, boolean blocks) {
        int bits = input.readUnsignedByte();
        boolean global = bits > (blocks ? 8 : 3);
        int outputBits = global && blocks ? NativeCustomBlocks.vanillaBits() : bits;
        output.writeByte(outputBits);
        if (bits == 0) {
            int id = input.readVarInt();
            output.writeVarInt(blocks ? NativeCustomBlocks.project(id) : id);
            if (!fixedPaletteLength) {
                if (input.readVarInt() != 0) throw new IllegalArgumentException("Nonempty single-value palette storage");
                output.writeVarInt(0);
            }
            return;
        }
        if (!global) {
            int length = input.readVarInt();
            output.writeVarInt(length);
            for (int i = 0; i < length; i++) {
                int id = input.readVarInt();
                output.writeVarInt(blocks ? NativeCustomBlocks.project(id) : id);
            }
        }
        int count = blocks ? 4096 : 64;
        int entriesPerLong = 64 / bits;
        int length = (count + entriesPerLong - 1) / entriesPerLong;
        if (!fixedPaletteLength && input.readVarInt() != length)
            throw new IllegalArgumentException("Invalid chunk palette storage length");
        int outputEntriesPerLong = 64 / outputBits;
        if (!fixedPaletteLength) output.writeVarInt((count + outputEntriesPerLong - 1) / outputEntriesPerLong);
        if (!global || !blocks) {
            output.writeBytes(input, length * Long.BYTES);
            return;
        }
        // A custom registry can require more bits than the vanilla client registry.
        // Repack global entries at the original vanilla width so its section decoder agrees.
        long mask = (1L << bits) - 1;
        long packed = 0;
        int outputIndex = 0;
        int index = 0;
        for (int i = 0; i < length; i++) {
            long source = input.readLong();
            for (int j = 0; j < entriesPerLong && index < count; j++, index++) {
                int id = (int) ((source >>> (j * bits)) & mask);
                packed |= (long) NativeCustomBlocks.project(id) << (outputIndex++ * outputBits);
                if (outputIndex == outputEntriesPerLong) {
                    output.writeLong(packed);
                    packed = 0;
                    outputIndex = 0;
                }
            }
        }
        if (outputIndex != 0) output.writeLong(packed);
    }
}
