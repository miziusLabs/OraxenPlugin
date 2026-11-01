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
import org.mockito.MockedStatic;

import java.util.Set;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeBlockStorageTest {
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
        NMSHandler handler = mock(NMSHandler.class);
        Chunk chunk = mock(Chunk.class);
        World world = mock(World.class);
        PersistentDataContainer chunkData = mock(PersistentDataContainer.class);
        PersistentDataContainer blockData = mock(PersistentDataContainer.class);
        AtomicInteger schema = new AtomicInteger();
        when(chunk.getWorld()).thenReturn(world);
        when(world.getMaxHeight()).thenReturn(1);
        when(chunk.getPersistentDataContainer()).thenReturn(chunkData);
        when(chunkData.getOrDefault(any(NamespacedKey.class), eq(PersistentDataType.INTEGER), eq(0)))
                .thenAnswer(invocation -> schema.get());
        doAnswer(invocation -> {
            schema.set(invocation.getArgument(2));
            return null;
        }).when(chunkData).set(any(NamespacedKey.class), eq(PersistentDataType.INTEGER), anyInt());
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
        NativeBlockStorage storage = new NativeBlockStorage(handler, variation -> variation == 0 ? key : null);

        try (MockedStatic<BlockHelpers> helpers = mockStatic(BlockHelpers.class)) {
            helpers.when(() -> BlockHelpers.getPDC(legacy)).thenReturn(blockData);
            helpers.when(() -> BlockHelpers.getBlocksWithCustomData(any(), eq(chunk))).thenReturn(Set.of());
            storage.migrateChunk(chunk, false);
            // Even a later vanilla note with the old appearance must not be converted again.
            storage.migrateChunk(chunk, false);
        }
        verify(legacy).setBlockData(nativeData, false);
        verify(blockData).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), eq(key));
    }

    @Test
    void newChunksNeverClassifyVanillaNotesAsLegacyCustomBlocks() {
        NMSHandler handler = mock(NMSHandler.class);
        Chunk chunk = mock(Chunk.class);
        when(chunk.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        NativeBlockStorage storage = new NativeBlockStorage(handler, variation -> {
            throw new AssertionError("A new chunk must not classify legacy note states");
        });
        try (MockedStatic<BlockHelpers> helpers = mockStatic(BlockHelpers.class)) {
            helpers.when(() -> BlockHelpers.getBlocksWithCustomData(any(), eq(chunk))).thenReturn(Set.of());
            storage.migrateChunk(chunk, true);
        }
        verify(chunk, never()).getBlock(anyInt(), anyInt(), anyInt());
        verifyNoInteractions(handler);
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
        NativeBlockStorage storage = new NativeBlockStorage(handler, variation -> null);

        try (MockedStatic<BlockHelpers> helpers = mockStatic(BlockHelpers.class)) {
            helpers.when(() -> BlockHelpers.getPDC(replacement)).thenReturn(data);
            storage.onPlace(event);
        }
        verify(data).remove(new NamespacedKey("oraxen", "native_block"));
        verify(replacement, never()).setBlockData(any(), anyBoolean());
    }
}
