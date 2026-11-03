package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import io.th0rgal.oraxen.nms.NMSHandler;
import io.th0rgal.oraxen.utils.BlockHelpers;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Instrument;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Note;
import org.bukkit.Server;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;

import java.util.Set;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NativeBlockStorageTest {
    @Test
    void shutdownClearsStorageInstanceForPluginReenable() throws Exception {
        Field instanceField = NativeBlockStorage.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        NativeBlockStorage oldStorage = new NativeBlockStorage(mock(NMSHandler.class));
        instanceField.set(null, oldStorage);

        NativeBlockStorage.shutdown();

        assertNull(instanceField.get(null));
    }

    @AfterEach
    void clearLegacyMappings() {
        NativeBlockStorage.shutdown();
        NativeBlockStorage.resetLegacyMappings();
        NativeBlockStorage.commitLegacyMappings();
    }

    @BeforeAll
    static void setUpServer() throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Server server = (Server) serverField.get(null);
        if (server == null) {
            server = mock(Server.class);
            serverField.set(null, server);
        }
        when(server.getTag(anyString(), any(NamespacedKey.class), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Tag<Material> tag = mock(Tag.class);
            when(tag.getValues()).thenReturn(Set.of());
            return tag;
        });
    }

    @Test
    void legacyConversionRunsOnceSoVanillaNotesPlacedLaterRemainVanilla() {
        NativeBlockStorage.resetLegacyMappings();
        NativeBlockStorage.registerLegacy(0, "test_block");
        NativeBlockStorage.commitLegacyMappings();
        NMSHandler handler = mock(NMSHandler.class);
        Chunk chunk = mock(Chunk.class);
        World world = mock(World.class);
        PersistentDataContainer chunkData = mock(PersistentDataContainer.class);
        PersistentDataContainer blockData = mock(PersistentDataContainer.class);
        AtomicInteger schema = new AtomicInteger();
        AtomicReference<String> migratedMappings = new AtomicReference<>(NativeBlockStorage.legacyMappingFingerprint());
        when(chunk.getWorld()).thenReturn(world);
        when(world.getMaxHeight()).thenReturn(1);
        when(chunk.getPersistentDataContainer()).thenReturn(chunkData);
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.INTEGER), eq(0)))
                .thenAnswer(invocation -> schema.get());
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.STRING), eq("")))
                .thenAnswer(invocation -> migratedMappings.get());
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.BYTE), eq((byte) 0)))
                .thenReturn((byte) 0);
        doAnswer(invocation -> {
            schema.set(invocation.getArgument(2));
            return null;
        }).when(chunkData).set(any(NamespacedKey.class), eq(PersistentDataType.INTEGER), anyInt());
        doAnswer(invocation -> {
            migratedMappings.set(invocation.getArgument(2));
            return null;
        }).when(chunkData).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenReturn(air);
        Block legacy = mock(Block.class);
        when(chunk.getBlock(0, 0, 0)).thenReturn(legacy);
        when(legacy.getType()).thenReturn(Material.NOTE_BLOCK);
        NoteBlock note = mock(NoteBlock.class);
        when(legacy.getBlockData()).thenReturn(note);
        when(note.getInstrument()).thenReturn(Instrument.BASS_DRUM);
        when(note.getNote()).thenReturn(new Note(1));
        String key = NativeBlockStorage.key("test_block");
        BlockData nativeData = mock(BlockData.class);
        when(handler.customBlockData(key)).thenReturn(nativeData);
        NativeBlockStorage storage = new NativeBlockStorage(handler);

        try (MockedStatic<BlockHelpers> helpers = mockStatic(BlockHelpers.class)) {
            helpers.when(() -> BlockHelpers.getPDC(legacy)).thenReturn(blockData);
            helpers.when(() -> BlockHelpers.getBlocksWithCustomData(any(), eq(chunk))).thenReturn(Set.of());
            storage.migrateChunk(chunk, false);
            Block vanillaPlacedLater = mock(Block.class);
            when(chunk.getBlock(1, 0, 0)).thenReturn(vanillaPlacedLater);
            when(vanillaPlacedLater.getType()).thenReturn(Material.NOTE_BLOCK);
            NoteBlock laterNote = mock(NoteBlock.class);
            when(vanillaPlacedLater.getBlockData()).thenReturn(laterNote);
            when(laterNote.getInstrument()).thenReturn(Instrument.BASS_DRUM);
            when(laterNote.getNote()).thenReturn(new Note(1));
            storage.migrateChunk(chunk, false);
            verify(vanillaPlacedLater, never()).setBlockData(any(BlockData.class), anyBoolean());
        }
        verify(legacy).setBlockData(nativeData, false);
        verify(blockData).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), eq(key));
    }

    @Test
    void chunksAreRescannedWhenAConfiguredLegacyMappingIsAddedLater() {
        NativeBlockStorage.resetLegacyMappings();
        NativeBlockStorage.commitLegacyMappings();
        String oldFingerprint = NativeBlockStorage.legacyMappingFingerprint();
        NativeBlockStorage.resetLegacyMappings();
        NativeBlockStorage.registerLegacy(0, "added_block");
        NativeBlockStorage.commitLegacyMappings();

        NMSHandler handler = mock(NMSHandler.class);
        Chunk chunk = mock(Chunk.class);
        World world = mock(World.class);
        PersistentDataContainer chunkData = mock(PersistentDataContainer.class);
        PersistentDataContainer blockData = mock(PersistentDataContainer.class);
        AtomicReference<String> migratedMappings = new AtomicReference<>(oldFingerprint);
        when(chunk.getWorld()).thenReturn(world);
        when(world.getMaxHeight()).thenReturn(1);
        when(chunk.getPersistentDataContainer()).thenReturn(chunkData);
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.INTEGER), eq(0))).thenReturn(1);
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.STRING), eq("")))
                .thenAnswer(invocation -> migratedMappings.get());
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.BYTE), eq((byte) 0)))
                .thenReturn((byte) 0);
        doAnswer(invocation -> {
            migratedMappings.set(invocation.getArgument(2));
            return null;
        }).when(chunkData).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenReturn(air);
        Block legacy = mock(Block.class);
        when(chunk.getBlock(0, 0, 0)).thenReturn(legacy);
        when(legacy.getType()).thenReturn(Material.NOTE_BLOCK);
        NoteBlock note = mock(NoteBlock.class);
        when(legacy.getBlockData()).thenReturn(note);
        when(note.getInstrument()).thenReturn(Instrument.BASS_DRUM);
        when(note.getNote()).thenReturn(new Note(1));
        String key = NativeBlockStorage.key("added_block");
        BlockData nativeData = mock(BlockData.class);
        when(handler.customBlockData(key)).thenReturn(nativeData);
        NativeBlockStorage storage = new NativeBlockStorage(handler);

        try (MockedStatic<BlockHelpers> helpers = mockStatic(BlockHelpers.class)) {
            helpers.when(() -> BlockHelpers.getPDC(legacy)).thenReturn(blockData);
            helpers.when(() -> BlockHelpers.getBlocksWithCustomData(any(), eq(chunk))).thenReturn(Set.of());
            storage.migrateChunk(chunk, false);
        }

        verify(legacy).setBlockData(nativeData, false);
        assertEquals(NativeBlockStorage.legacyMappingFingerprint(), migratedMappings.get());
    }

    @Test
    void freshChunksDuringMappingReloadKeepSkippingLegacyClassification() {
        NativeBlockStorage.resetLegacyMappings();
        NativeBlockStorage.registerLegacy(0, "new_mapping_during_reload");
        NMSHandler handler = mock(NMSHandler.class);
        Chunk chunk = mock(Chunk.class);
        PersistentDataContainer chunkData = mock(PersistentDataContainer.class);
        AtomicInteger schema = new AtomicInteger();
        AtomicReference<String> migratedMappings = new AtomicReference<>("");
        AtomicInteger legacySkipped = new AtomicInteger();
        when(chunk.getPersistentDataContainer()).thenReturn(chunkData);
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.INTEGER), eq(0)))
                .thenAnswer(invocation -> schema.get());
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.STRING), eq("")))
                .thenAnswer(invocation -> migratedMappings.get());
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.BYTE), eq((byte) 0)))
                .thenAnswer(invocation -> (byte) legacySkipped.get());
        doAnswer(invocation -> {
            schema.set(invocation.getArgument(2));
            return null;
        }).when(chunkData).set(any(NamespacedKey.class), eq(PersistentDataType.INTEGER), anyInt());
        doAnswer(invocation -> {
            migratedMappings.set(invocation.getArgument(2));
            return null;
        }).when(chunkData).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        doAnswer(invocation -> {
            legacySkipped.set((Byte) invocation.getArgument(2));
            return null;
        }).when(chunkData).set(any(NamespacedKey.class), eq(PersistentDataType.BYTE), anyByte());
        NativeBlockStorage storage = new NativeBlockStorage(handler);
        try (MockedStatic<BlockHelpers> helpers = mockStatic(BlockHelpers.class)) {
            helpers.when(() -> BlockHelpers.getBlocksWithCustomData(any(), eq(chunk))).thenReturn(Set.of());
            storage.migrateChunk(chunk, true);
            NativeBlockStorage.commitLegacyMappings();
            storage.migrateChunk(chunk, false);
        }
        verify(chunk, never()).getBlock(anyInt(), anyInt(), anyInt());
        verifyNoInteractions(handler);
        verify(chunkData).set(eq(new NamespacedKey("oraxen", "native_block_legacy_skipped")),
                eq(PersistentDataType.BYTE), eq((byte) 1));
        assertEquals(NativeBlockStorage.legacyMappingFingerprint(), migratedMappings.get());
    }

    @Test
    void unrelatedReplacementClearsIdentityWithoutRestoringTheOldBlock() {
        NMSHandler handler = mock(NMSHandler.class);
        Block replacement = mock(Block.class);
        BlockData stone = mock(BlockData.class);
        when(replacement.getBlockData()).thenReturn(stone);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.has(any(NamespacedKey.class))).thenReturn(true);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getBlock()).thenReturn(replacement);
        NativeBlockStorage storage = new NativeBlockStorage(handler);

        try (MockedStatic<BlockHelpers> helpers = mockStatic(BlockHelpers.class)) {
            helpers.when(() -> BlockHelpers.getPDC(replacement)).thenReturn(data);
            storage.onPlace(event);
        }
        verify(data).remove(new NamespacedKey("oraxen", "native_block"));
        verify(replacement, never()).setBlockData(any(), anyBoolean());
    }
}
