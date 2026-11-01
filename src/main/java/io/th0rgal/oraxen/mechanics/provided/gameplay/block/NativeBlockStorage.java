package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import io.th0rgal.oraxen.OraxenPlugin;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.th0rgal.oraxen.nms.NMSHandler;
import io.th0rgal.oraxen.nms.NMSHandlers;
import io.th0rgal.oraxen.utils.BlockHelpers;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/** Persists full-block identities and converts legacy note states once per chunk. */
public final class NativeBlockStorage implements Listener {
    private static final NamespacedKey BLOCK_KEY = new NamespacedKey("oraxen", "native_block");
    private static final NamespacedKey SCHEMA_KEY = new NamespacedKey("oraxen", "native_block_schema");
    private static final int SCHEMA_VERSION = 1;
    private static final Map<Integer, String> LEGACY_KEYS = new ConcurrentHashMap<>();
    private static NativeBlockStorage instance;

    private final NMSHandler handler;
    private final IntFunction<String> legacyKeys;
    private final AtomicBoolean migrationFailed = new AtomicBoolean();

    NativeBlockStorage(NMSHandler handler, IntFunction<String> legacyKeys) {
        this.handler = handler;
        this.legacyKeys = legacyKeys;
    }

    public static String key(String itemId) {
        String normalized = itemId.toLowerCase(Locale.ROOT);
        String path = normalized.matches("[a-z0-9._/-]+") ? normalized
                : "encoded/" + HexFormat.of().formatHex(itemId.getBytes(StandardCharsets.UTF_8));
        return "oraxen:block/" + path;
    }

    /** Reset before parsing a complete set of item definitions, including on reload. */
    public static void resetLegacyMappings() {
        LEGACY_KEYS.clear();
    }

    /** Register only variations explicitly present in the original item configuration. */
    public static void registerLegacy(int variation, String itemId) {
        String key = key(itemId);
        String previous = LEGACY_KEYS.putIfAbsent(variation, key);
        if (previous != null && !previous.equals(key))
            throw new IllegalArgumentException("Legacy variation " + variation + " conflicts between " + previous + " and " + key);
    }

    /** Call after item definitions and their native blocks have registered. */
    public static void initialize() {
        initialize(() -> {});
    }

    /** The callback runs globally after every currently loaded chunk has migrated successfully. */
    public static void initialize(Runnable afterMigration) {
        if (instance != null) HandlerList.unregisterAll(instance);
        NMSHandler handler = NMSHandlers.getHandler();
        if (!handler.supportsNativeCustomBlocks()) {
            instance = null;
            return;
        }
        instance = new NativeBlockStorage(handler, LEGACY_KEYS::get);
        NativeBlockStorage storage = instance;
        AtomicInteger remaining = new AtomicInteger(1);
        Runnable completed = () -> {
            if (remaining.decrementAndGet() == 0 && !storage.migrationFailed.get()) {
                SchedulerUtil.runTask(() -> {
                    if (instance == storage && !storage.migrationFailed.get()) afterMigration.run();
                });
            }
        };
        Bukkit.getPluginManager().registerEvents(storage, OraxenPlugin.get());
        Bukkit.getWorlds().forEach(world -> {
            for (Chunk chunk : world.getLoadedChunks()) {
                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();
                Location location = new Location(world, chunkX << 4, world.getMinHeight(), chunkZ << 4);
                remaining.incrementAndGet();
                Runnable migrate = () -> {
                    try {
                        if (instance == storage && world.isChunkLoaded(chunkX, chunkZ))
                            storage.migrateTracked(world.getChunkAt(chunkX, chunkZ), false);
                    } finally {
                        completed.run();
                    }
                };
                if (Bukkit.isOwnedByCurrentRegion(location)) migrate.run();
                else {
                    try {
                        SchedulerUtil.runAtLocation(location, migrate);
                    } catch (RuntimeException exception) {
                        storage.migrationFailed.set(true);
                        completed.run();
                        throw exception;
                    }
                }
            }
        });
        completed.run();
    }

    public static void record(Block block, String key) {
        if (key == null) BlockHelpers.getPDC(block).remove(BLOCK_KEY);
        else {
            PersistentDataContainer data = BlockHelpers.getPDC(block);
            if (!key.equals(data.get(BLOCK_KEY, PersistentDataType.STRING))) data.set(BLOCK_KEY, PersistentDataType.STRING, key);
            if (instance != null) instance.handler.refreshNativeBlockLight(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNativePhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        String key = handler.customBlockKey(block.getBlockData());
        if (key != null) record(block, key);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        Location location = new Location(chunk.getWorld(), chunk.getX() << 4, chunk.getWorld().getMinHeight(), chunk.getZ() << 4);
        SchedulerUtil.runAtLocation(location, () -> {
            if (!location.isChunkLoaded()) return;
            for (Block block : BlockHelpers.getBlocksWithCustomData(OraxenPlugin.get(), location.getChunk()))
                if (handler.customBlockKey(block.getBlockData()) != null) handler.refreshNativeBlockLight(block);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        migrateTracked(event.getChunk(), event.isNewChunk());
    }

    private void migrateTracked(Chunk chunk, boolean fresh) {
        try {
            migrateChunk(chunk, fresh);
        } catch (RuntimeException exception) {
            migrationFailed.set(true);
            Logs.logError("Could not migrate native blocks in chunk " + chunk.getX() + ", " + chunk.getZ()
                    + " in " + chunk.getWorld().getName());
            exception.printStackTrace();
            throw exception;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        synchronizeMarker(event.getBlock());
    }

    /** Must execute on the thread that owns this chunk. */
    public void migrateChunk(Chunk chunk, boolean fresh) {
        PersistentDataContainer chunkData = chunk.getPersistentDataContainer();
        boolean legacy = !fresh && chunkData.getOrDefault(SCHEMA_KEY, PersistentDataType.INTEGER, 0) < SCHEMA_VERSION;
        if (legacy) {
            for (int y = chunk.getWorld().getMinHeight(); y < chunk.getWorld().getMaxHeight(); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) migrateLegacy(chunk.getBlock(x, y, z));
                }
            }
        }
        // A stale marker must never restore AIR or overwrite an unrelated replacement.
        for (Block block : BlockHelpers.getBlocksWithCustomData(OraxenPlugin.get(), chunk))
            synchronizeMarker(block);
        chunkData.set(SCHEMA_KEY, PersistentDataType.INTEGER, SCHEMA_VERSION);
    }

    private void migrateLegacy(Block block) {
        if (block.getType() != Material.NOTE_BLOCK) return;
        BlockData data = block.getBlockData();
        String nativeKey = handler.customBlockKey(data);
        if (nativeKey != null) {
            record(block, nativeKey);
            return;
        }
        if (!(data instanceof NoteBlock note)) return;
        int variation = note.getInstrument().getType() * 25 + note.getNote().getId()
                + (note.isPowered() ? 400 : 0) - 26;
        if (variation < 0) return;
        String key = legacyKeys.apply(variation);
        if (key == null) return;
        BlockData replacement = handler.customBlockData(key);
        if (replacement == null) throw new IllegalStateException("Native block is unavailable for legacy migration " + key);
        var mechanic = io.th0rgal.oraxen.mechanics.provided.gameplay.noteblock.NoteBlockMechanicFactory.getNativeMechanic(key);
        if (mechanic != null && mechanic.hasLight()) mechanic.getLight().removeBlockLight(block);
        block.setBlockData(replacement, false);
        record(block, key);
    }

    private void synchronizeMarker(Block block) {
        String key = handler.customBlockKey(block.getBlockData());
        if (key != null) {
            record(block, key);
        }
        else {
            PersistentDataContainer data = BlockHelpers.getPDC(block);
            if (data.has(BLOCK_KEY)) data.remove(BLOCK_KEY);
        }
    }
}
