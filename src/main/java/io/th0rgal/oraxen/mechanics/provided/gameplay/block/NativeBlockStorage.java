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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Collection;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Persists full-block identities and converts legacy note states once per chunk. */
public final class NativeBlockStorage implements Listener {
    private static final NamespacedKey BLOCK_KEY = new NamespacedKey("oraxen", "native_block");
    private static final NamespacedKey SCHEMA_KEY = new NamespacedKey("oraxen", "native_block_schema");
    private static final NamespacedKey LEGACY_MAPPING_KEY = new NamespacedKey("oraxen", "native_block_legacy_mapping");
    private static final NamespacedKey LEGACY_SKIPPED_KEY = new NamespacedKey("oraxen", "native_block_legacy_skipped");
    private static final int SCHEMA_VERSION = 1;
    private static volatile LegacyMappings LEGACY_MAPPINGS = legacyMappings(Map.of());
    private static Map<Integer, String> pendingLegacyKeys;
    private static volatile boolean legacyMappingsReloading;
    private static volatile boolean reloadInProgress;
    private static volatile NativeBlockStorage instance;

    private final NMSHandler handler;
    private final AtomicLong initializationGeneration = new AtomicLong();
    private volatile boolean refreshAppearances;
    private final AtomicBoolean migrationFailed = new AtomicBoolean();
    private volatile MigrationRun migrationRun;

    NativeBlockStorage(NMSHandler handler) {
        this(handler, false);
    }

    NativeBlockStorage(NMSHandler handler, boolean refreshAppearances) {
        this.handler = handler;
        this.refreshAppearances = refreshAppearances;
    }

    public static String key(String itemId) {
        String normalized = itemId.toLowerCase(Locale.ROOT);
        String path = normalized.matches("[a-z0-9._/-]+") ? normalized
                : "encoded/" + HexFormat.of().formatHex(itemId.getBytes(StandardCharsets.UTF_8));
        return "oraxen:block/" + path;
    }

    /** Stage legacy mappings while item definitions load, leaving the active snapshot available to region threads. */
    public static synchronized void resetLegacyMappings() {
        beginReloadTransaction();
        pendingLegacyKeys = new HashMap<>();
    }

    /** Protects native blocks before a full reload starts replacing mechanic listeners. */
    public static synchronized void beginReload() {
        beginReloadTransaction();
    }

    private static void beginReloadTransaction() {
        legacyMappingsReloading = true;
        reloadInProgress = true;
        NativeBlockStorage storage = instance;
        if (storage != null) {
            storage.initializationGeneration.incrementAndGet();
            storage.refreshAppearances = true;
        }
    }

    /** Publish mappings only after the full item set has loaded. */
    public static synchronized void commitLegacyMappings() {
        if (pendingLegacyKeys == null) return;
        LEGACY_MAPPINGS = legacyMappings(pendingLegacyKeys);
        pendingLegacyKeys = null;
        legacyMappingsReloading = false;
        if (instance == null) reloadInProgress = false;
    }

    /** Discard an incomplete mapping set after an item load fails. */
    public static synchronized void discardLegacyMappings() {
        pendingLegacyKeys = null;
        legacyMappingsReloading = false;
        if (instance == null) reloadInProgress = false;
    }

    static String legacyKey(int variation) {
        return LEGACY_MAPPINGS.keys().get(variation);
    }

    private static LegacyMappings legacyMappings(Map<Integer, String> keys) {
        Map<Integer, String> immutableKeys = Map.copyOf(keys);
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(immutableKeys).forEach((variation, key) ->
                canonical.append(variation).append('=').append(key).append('\n'));
        try {
            byte[] fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return new LegacyMappings(immutableKeys, HexFormat.of().formatHex(fingerprint));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record LegacyMappings(Map<Integer, String> keys, String fingerprint) {}

    private record LoadedChunk(UUID worldId, int x, int z) {
    }

    private enum MigrationReservation {
        SCHEDULED,
        DUPLICATE,
        CLOSED
    }

    private static final class MigrationRun {
        private final Object lock = new Object();
        private final Set<LoadedChunk> seenChunks = new HashSet<>();
        private final Set<LoadedChunk> pendingChunks = new HashSet<>();
        private final Runnable completion;
        private final long generation;
        private int pending = 1;
        private boolean scanFinished;
        private boolean completionRequested;
        private boolean closed;

        private MigrationRun(long generation, Runnable completion) {
            this.generation = generation;
            this.completion = completion;
        }

        private MigrationReservation reserve(UUID worldId, int chunkX, int chunkZ) {
            synchronized (lock) {
                if (closed) return MigrationReservation.CLOSED;
                LoadedChunk chunk = new LoadedChunk(worldId, chunkX, chunkZ);
                if (!seenChunks.add(chunk)) return MigrationReservation.DUPLICATE;
                pendingChunks.add(chunk);
                pending++;
                return MigrationReservation.SCHEDULED;
            }
        }

        private void complete(UUID worldId, int chunkX, int chunkZ) {
            boolean finish;
            synchronized (lock) {
                if (!pendingChunks.remove(new LoadedChunk(worldId, chunkX, chunkZ))) return;
                pending--;
                finish = requestCompletionIfReady();
            }
            if (finish) completion.run();
        }

        private void finishScan() {
            boolean finish;
            synchronized (lock) {
                if (scanFinished) return;
                scanFinished = true;
                pending--;
                finish = requestCompletionIfReady();
            }
            if (finish) completion.run();
        }

        private boolean requestCompletionIfReady() {
            if (closed || completionRequested || !scanFinished || pending != 0) return false;
            completionRequested = true;
            return true;
        }

        private boolean tryClose() {
            synchronized (lock) {
                completionRequested = false;
                if (closed || !scanFinished || pending != 0) return false;
                closed = true;
                return true;
            }
        }

    }

    /** Validate a legacy mapping before any native identity is persisted. */
    public static synchronized void validateLegacy(int variation, String itemId) {
        if (!NativeBlockRegistry.isValidLegacyVariation(variation))
            throw new IllegalArgumentException("Legacy custom variation must be between 0 and "
                    + (NativeBlockRegistry.SLOT_COUNT - 1) + ": " + variation);
        String key = key(itemId);
        Map<Integer, String> mappings = pendingLegacyKeys == null ? LEGACY_MAPPINGS.keys() : pendingLegacyKeys;
        String previous = mappings.get(variation);
        if (previous != null && !previous.equals(key))
            throw new IllegalArgumentException("Legacy variation " + variation + " conflicts between " + previous + " and " + key);
    }

    /** Register only valid variations explicitly present in the original item configuration. */
    public static synchronized void registerLegacy(int variation, String itemId) {
        validateLegacy(variation, itemId);
        if (pendingLegacyKeys != null) {
            pendingLegacyKeys.putIfAbsent(variation, key(itemId));
        } else if (!LEGACY_MAPPINGS.keys().containsKey(variation)) {
            Map<Integer, String> updated = new HashMap<>(LEGACY_MAPPINGS.keys());
            updated.put(variation, key(itemId));
            LEGACY_MAPPINGS = legacyMappings(updated);
        }
    }

    /** Call after item definitions and their native blocks have registered. */
    public static void initialize() {
        initialize(false, () -> {});
    }

    /** The callback runs globally after every currently loaded chunk has migrated successfully. */
    public static void initialize(Runnable afterMigration) {
        initialize(false, afterMigration);
    }

    /** Refreshes live block appearances and lighting after item definitions change. */
    public static void initializeForReload() {
        initialize(true, () -> {});
    }

    /** Clears listeners and pending migrations when the plugin disables. */
    public static synchronized void shutdown() {
        unregisterStorage();
        LEGACY_MAPPINGS = legacyMappings(Map.of());
        pendingLegacyKeys = null;
        legacyMappingsReloading = false;
        reloadInProgress = false;
    }

    private static synchronized void unregisterStorage() {
        NativeBlockStorage storage = instance;
        if (storage != null) {
            storage.initializationGeneration.incrementAndGet();
            storage.migrationRun = null;
            instance = null;
            HandlerList.unregisterAll(storage);
        }
    }

    private static void initialize(boolean refreshAppearances, Runnable afterMigration) {
        if (!SchedulerUtil.isGlobalThread()) {
            SchedulerUtil.runTask(() -> initialize(refreshAppearances, afterMigration));
            return;
        }
        NMSHandler handler = NMSHandlers.getHandler();
        if (!handler.supportsNativeCustomBlocks()) {
            unregisterStorage();
            legacyMappingsReloading = false;
            reloadInProgress = false;
            return;
        }
        NativeBlockStorage currentStorage = instance;
        boolean registerListener = currentStorage == null;
        if (registerListener) {
            currentStorage = new NativeBlockStorage(handler, refreshAppearances);
            instance = currentStorage;
        } else {
            currentStorage.refreshAppearances = refreshAppearances;
        }
        NativeBlockStorage storage = currentStorage;
        LegacyMappings legacyMappings = LEGACY_MAPPINGS;
        final long generation;
        final MigrationRun migrationRun;
        synchronized (storage) {
            generation = storage.initializationGeneration.incrementAndGet();
            storage.migrationFailed.set(false);
            Runnable completed = () -> {
                Runnable finish = () -> {
                    synchronized (storage) {
                        if (instance != storage || storage.initializationGeneration.get() != generation) return;
                        MigrationRun activeRun = storage.migrationRun;
                        if (activeRun == null || activeRun.generation != generation || !activeRun.tryClose()) return;
                        storage.migrationRun = null;
                    }
                    try {
                        if (!storage.migrationFailed.get()) afterMigration.run();
                    } finally {
                        if (storage.initializationGeneration.get() == generation) {
                            storage.refreshAppearances = false;
                            legacyMappingsReloading = false;
                            reloadInProgress = false;
                        }
                        storage.handler.finishNativeBlockReload();
                    }
                };
                try {
                    SchedulerUtil.runTask(finish);
                } catch (RuntimeException exception) {
                    synchronized (storage) {
                        if (instance == storage && storage.initializationGeneration.get() == generation) {
                            storage.migrationRun = null;
                            storage.refreshAppearances = false;
                            legacyMappingsReloading = false;
                            reloadInProgress = false;
                            storage.handler.finishNativeBlockReload();
                        }
                    }
                    throw exception;
                }
            };
            migrationRun = new MigrationRun(generation, completed);
            storage.migrationRun = migrationRun;
        }
        Runnable scanLoadedChunks = () -> {
            try {
                if (storage.initializationGeneration.get() != generation) return;
                Map<UUID, World> worlds = new HashMap<>();
                Bukkit.getWorlds().forEach(world -> worlds.put(world.getUID(), world));
                for (World world : worlds.values()) {
                    if (storage.initializationGeneration.get() != generation) break;
                    Collection<NMSHandler.ChunkCoordinate> coordinates = handler.loadedChunkCoordinates(world);
                    for (NMSHandler.ChunkCoordinate coordinate : coordinates) {
                        if (storage.initializationGeneration.get() != generation) break;
                        scheduleMigration(storage, migrationRun, legacyMappings,
                                world, coordinate.x(), coordinate.z());
                    }
                }
            } catch (RuntimeException exception) {
                if (storage.initializationGeneration.get() == generation)
                    storage.migrationFailed.set(true);
                throw exception;
            } finally {
                migrationRun.finishScan();
            }
        };
        try {
            if (registerListener) Bukkit.getPluginManager().registerEvents(storage, OraxenPlugin.get());
            SchedulerUtil.runTask(scanLoadedChunks);
        } catch (RuntimeException exception) {
            if (storage.initializationGeneration.get() == generation) {
                storage.migrationFailed.set(true);
                legacyMappingsReloading = false;
                reloadInProgress = false;
            }
            try {
                migrationRun.finishScan();
            } catch (RuntimeException completionException) {
                exception.addSuppressed(completionException);
            }
            throw exception;
        }
    }

    private static void scheduleMigration(NativeBlockStorage storage, MigrationRun migrationRun,
                                          LegacyMappings legacyMappings, World world, int chunkX, int chunkZ) {
        MigrationReservation reservation = migrationRun.reserve(world.getUID(), chunkX, chunkZ);
        if (reservation != MigrationReservation.SCHEDULED) return;
        long generation = migrationRun.generation;
        Location location = new Location(world, chunkX << 4, world.getMinHeight(), chunkZ << 4);
        Runnable migrate = () -> {
            try {
                if (instance == storage && storage.initializationGeneration.get() == generation
                        && !legacyMappingsReloading && LEGACY_MAPPINGS == legacyMappings
                        && world.isChunkLoaded(chunkX, chunkZ)) {
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    try {
                        storage.migrateTracked(chunk, false, generation, legacyMappings);
                    } catch (RuntimeException exception) {
                        storage.refreshTrackedBlocks(chunk, generation, legacyMappings);
                        throw exception;
                    }
                }
            } finally {
                migrationRun.complete(world.getUID(), chunkX, chunkZ);
            }
        };
        try {
            if (Bukkit.isOwnedByCurrentRegion(location)) migrate.run();
            else SchedulerUtil.runAtLocation(location, migrate);
        } catch (RuntimeException exception) {
            if (storage.initializationGeneration.get() == generation)
                storage.migrationFailed.set(true);
            migrationRun.complete(world.getUID(), chunkX, chunkZ);
            throw exception;
        }
    }

    public static void record(Block block, String key) {
        if (key == null) BlockHelpers.getPDC(block).remove(BLOCK_KEY);
        else {
            PersistentDataContainer data = BlockHelpers.getPDC(block);
            if (!key.equals(data.get(BLOCK_KEY, PersistentDataType.STRING))) data.set(BLOCK_KEY, PersistentDataType.STRING, key);
            NativeBlockStorage storage = instance;
            if (storage != null) {
                storage.handler.refreshNativeBlockLight(block);
                if (storage.refreshAppearances) storage.handler.refreshNativeBlockAppearance(block);
            }
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
        long generation = initializationGeneration.get();
        SchedulerUtil.runAtLocation(location, () -> {
            if (initializationGeneration.get() != generation || legacyMappingsReloading || !location.isChunkLoaded()) return;
            Chunk loadedChunk = location.getChunk();
            LegacyMappings legacyMappings = LEGACY_MAPPINGS;
            try {
                migrateTracked(loadedChunk, false, generation, legacyMappings);
            } catch (RuntimeException exception) {
                refreshTrackedBlocks(loadedChunk, generation, legacyMappings);
                return;
            }
            for (Block block : BlockHelpers.getBlocksWithCustomData(OraxenPlugin.get(), location.getChunk()))
                if (handler.customBlockKey(block.getBlockData()) != null) {
                    handler.refreshNativeBlockLight(block);
                    if (initializationGeneration.get() != generation) return;
                    if (refreshAppearances) handler.refreshNativeBlockAppearance(block);
                }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        migrateLoadedChunk(event.getChunk(), event.isNewChunk());
    }

    private void migrateLoadedChunk(Chunk chunk, boolean fresh) {
        long generation;
        LegacyMappings legacyMappings;
        MigrationRun run;
        MigrationReservation reservation;
        synchronized (this) {
            generation = initializationGeneration.get();
            legacyMappings = LEGACY_MAPPINGS;
            run = migrationRun;
            if (run == null || run.generation != generation) {
                run = null;
                reservation = MigrationReservation.CLOSED;
            } else {
                reservation = run.reserve(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
            }
        }
        if (run == null) {
            migrateTrackedWithRefreshFallback(chunk, fresh, generation, legacyMappings);
            return;
        }

        if (reservation == MigrationReservation.DUPLICATE) {
            // Preserve the new-chunk rule if the coordinate scan reserved this chunk first.
            if (fresh) migrateTrackedWithRefreshFallback(chunk, true, generation, legacyMappings);
            return;
        }
        if (reservation == MigrationReservation.CLOSED) {
            migrateTrackedWithRefreshFallback(chunk, fresh, generation, legacyMappings);
            return;
        }

        try {
            migrateTrackedWithRefreshFallback(chunk, fresh, generation, legacyMappings);
        } finally {
            run.complete(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }

    private void migrateTrackedWithRefreshFallback(Chunk chunk, boolean fresh, long generation,
                                                    LegacyMappings legacyMappings) {
        try {
            migrateTracked(chunk, fresh, generation, legacyMappings);
        } catch (RuntimeException exception) {
            refreshTrackedBlocks(chunk, generation, legacyMappings);
        }
    }

    private void refreshTrackedBlocks(Chunk chunk, long generation, LegacyMappings legacyMappings) {
        try {
            for (Block block : BlockHelpers.getBlocksWithCustomData(OraxenPlugin.get(), chunk)) {
                if (!migrationIsCurrent(generation, legacyMappings)) return;
                if (handler.customBlockKey(block.getBlockData()) == null) continue;
                try {
                    handler.refreshNativeBlockLight(block);
                    if (refreshAppearances) handler.refreshNativeBlockAppearance(block);
                } catch (RuntimeException exception) {
                    migrationFailed.set(true);
                    Logs.logError("Could not refresh native block at " + block.getX() + ", " + block.getY() + ", "
                            + block.getZ() + " in " + chunk.getWorld().getName());
                    exception.printStackTrace();
                }
            }
        } catch (RuntimeException exception) {
            migrationFailed.set(true);
            Logs.logError("Could not refresh native blocks in chunk " + chunk.getX() + ", " + chunk.getZ()
                    + " in " + chunk.getWorld().getName());
            exception.printStackTrace();
        }
    }

    private void migrateTracked(Chunk chunk, boolean fresh) {
        migrateTracked(chunk, fresh, initializationGeneration.get(), LEGACY_MAPPINGS);
    }

    private void migrateTracked(Chunk chunk, boolean fresh, long generation, LegacyMappings legacyMappings) {
        if (fresh) markLegacyClassificationSkipped(chunk);
        if (initializationGeneration.get() != generation || legacyMappingsReloading
                || LEGACY_MAPPINGS != legacyMappings) return;
        try {
            migrateChunk(chunk, fresh, generation, legacyMappings);
        } catch (RuntimeException exception) {
            if (initializationGeneration.get() == generation) migrationFailed.set(true);
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void protectNativeBlocksDuringReload(BlockBreakEvent event) {
        if (reloadInProgress && handler.customBlockKey(event.getBlock().getBlockData()) != null)
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void protectNativeBlockInteractionsDuringReload(org.bukkit.event.player.PlayerInteractEvent event) {
        if (!reloadInProgress) return;
        Block block = event.getClickedBlock();
        if (block != null && handler.customBlockKey(block.getBlockData()) != null) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            String itemId = io.th0rgal.oraxen.api.OraxenItems.getIdByItem(event.getItem());
            if (io.th0rgal.oraxen.mechanics.provided.gameplay.noteblock.NoteBlockMechanicFactory
                    .getItemMechanic(itemId) != null)
                event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void protectNativeBlocksDuringReload(BlockExplodeEvent event) {
        if (reloadInProgress)
            event.blockList().removeIf(block -> handler.customBlockKey(block.getBlockData()) != null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void protectNativeBlocksDuringReload(EntityExplodeEvent event) {
        if (reloadInProgress)
            event.blockList().removeIf(block -> handler.customBlockKey(block.getBlockData()) != null);
    }

    /** Must execute on the thread that owns this chunk. */
    public void migrateChunk(Chunk chunk, boolean fresh) {
        migrateChunk(chunk, fresh, initializationGeneration.get(), LEGACY_MAPPINGS);
    }

    static String legacyMappingFingerprint() {
        return LEGACY_MAPPINGS.fingerprint();
    }

    private void migrateChunk(Chunk chunk, boolean fresh, long generation, LegacyMappings legacyMappings) {
        if (fresh) markLegacyClassificationSkipped(chunk);
        if (!migrationIsCurrent(generation, legacyMappings)) return;
        PersistentDataContainer chunkData = chunk.getPersistentDataContainer();
        int schema = chunkData.getOrDefault(SCHEMA_KEY, PersistentDataType.INTEGER, 0);
        String migratedMappings = chunkData.getOrDefault(LEGACY_MAPPING_KEY, PersistentDataType.STRING, "");
        boolean legacySkipped = chunkData.getOrDefault(LEGACY_SKIPPED_KEY, PersistentDataType.BYTE, (byte) 0) != 0;
        boolean legacy = !fresh && !legacySkipped
                && (schema < SCHEMA_VERSION || !legacyMappings.fingerprint().equals(migratedMappings));
        if (legacy) {
            for (int y = chunk.getWorld().getMinHeight(); y < chunk.getWorld().getMaxHeight(); y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!migrationIsCurrent(generation, legacyMappings)) return;
                        migrateLegacy(chunk.getBlock(x, y, z), legacyMappings);
                    }
                }
            }
        }
        // A stale marker must never restore AIR or overwrite an unrelated replacement.
        for (Block block : BlockHelpers.getBlocksWithCustomData(OraxenPlugin.get(), chunk)) {
            if (!migrationIsCurrent(generation, legacyMappings)) return;
            synchronizeMarker(block);
        }
        if (!migrationIsCurrent(generation, legacyMappings)) return;
        chunkData.set(SCHEMA_KEY, PersistentDataType.INTEGER, SCHEMA_VERSION);
        chunkData.set(LEGACY_MAPPING_KEY, PersistentDataType.STRING, legacyMappings.fingerprint());
    }

    private void markLegacyClassificationSkipped(Chunk chunk) {
        PersistentDataContainer chunkData = chunk.getPersistentDataContainer();
        if (chunkData.getOrDefault(LEGACY_SKIPPED_KEY, PersistentDataType.BYTE, (byte) 0) == 0)
            chunkData.set(LEGACY_SKIPPED_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean migrationIsCurrent(long generation, LegacyMappings legacyMappings) {
        return initializationGeneration.get() == generation && !legacyMappingsReloading
                && LEGACY_MAPPINGS == legacyMappings;
    }

    private void migrateLegacy(Block block, LegacyMappings legacyMappings) {
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
        String key = legacyMappings.keys().get(variation);
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
