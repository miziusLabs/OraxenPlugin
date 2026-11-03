package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import com.google.gson.*;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.nms.NMSHandler;
import io.th0rgal.oraxen.nms.NMSHandlers;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Durable native block identities and stable appearance allocations.
 * The JSON file is part of world persistence and must accompany world backups.
 * It is not a disposable cache. Native registrations must exist before chunks load.
 */
public final class NativeBlockRegistry {
    /** Full custom blocks share the same allocation pool as their note-block appearances. */
    public static final int SLOT_COUNT = 774;
    private static final String SLOT_BLOCK_PREFIX = "oraxen:native_block/slot_";
    private static final Map<String, JsonObject> entries = new LinkedHashMap<>();
    private static Map<String, JsonObject> pendingEntries;
    private static Map<String, Registration> pendingRegistrations;
    private static boolean loaded;

    private NativeBlockRegistry() {}

    public static boolean enabled() {
        return VersionUtil.atOrAbove("1.21.4") && NMSHandlers.getHandler().supportsNativeCustomBlocks();
    }

    private static Path cache() {
        return OraxenPlugin.get().getDataFolder().toPath().resolve("native-blocks.json");
    }

    private static synchronized void load() {
        if (loaded) return;
        if (Files.exists(cache())) {
            try (var reader = Files.newBufferedReader(cache(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (JsonElement element : root.getAsJsonArray("blocks")) {
                    JsonObject entry = element.getAsJsonObject();
                    entries.put(entry.get("itemId").getAsString(), entry);
                }
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Cannot read native block identities", exception);
            }
        }
        loaded = true;
    }

    private static Map<String, JsonObject> allocations() {
        return pendingEntries == null ? entries : pendingEntries;
    }

    private static Map<String, JsonObject> copyEntries(Map<String, JsonObject> source) {
        Map<String, JsonObject> copy = new LinkedHashMap<>();
        source.forEach((itemId, entry) -> copy.put(itemId, entry.deepCopy()));
        return copy;
    }

    /** Begin a native identity transaction for one complete item reload. */
    public static synchronized void beginReload() {
        if (!enabled()) return;
        load();
        pendingEntries = copyEntries(entries);
        pendingRegistrations = new LinkedHashMap<>();
    }

    /** Discard all native identity and appearance changes staged during a failed item reload. */
    public static synchronized void discardReload() {
        pendingEntries = null;
        pendingRegistrations = null;
    }

    /** Persist staged identities and publish their complete NMS mapping in one batch. */
    public static synchronized void commitReload() {
        if (pendingEntries == null || pendingRegistrations == null) return;
        Map<String, JsonObject> staged = copyEntries(pendingEntries);
        staged.entrySet().removeIf(allocation -> !allocation.getValue().has("key"));
        List<NMSHandler.CustomBlockRegistration> registrations = new java.util.ArrayList<>();
        for (Registration registration : pendingRegistrations.values()) {
            JsonObject entry = staged.get(registration.itemId());
            if (entry == null || !entry.has("key"))
                throw new IllegalStateException("Native block registration has no staged identity " + registration.itemId());
            registrations.add(new NMSHandler.CustomBlockRegistration(entry.get("key").getAsString(),
                    entry.get("variation").getAsInt(), registration.appearance(), entry.get("light").getAsInt()));
        }
        commit(staged, registrations);
        pendingEntries = null;
        pendingRegistrations = null;
    }

    public static synchronized int variation(String itemId, Integer legacy) {
        load();
        return allocateVariation(allocations(), itemId, legacy);
    }

    /** Preflights all requested identities without changing the registry. */
    public static synchronized Map<String, Integer> reserveVariations(Map<String, Integer> requested) {
        load();
        return reserveVariations(allocations(), requested);
    }

    static Map<String, Integer> reserveVariations(Map<String, JsonObject> allocations,
                                                   Map<String, Integer> requested) {
        Map<String, JsonObject> staged = new LinkedHashMap<>();
        allocations.forEach((itemId, entry) -> staged.put(itemId, entry.deepCopy()));

        Map<String, Integer> reservations = new LinkedHashMap<>();
        requested.forEach((itemId, legacy) ->
                reservations.put(itemId, allocateVariation(staged, itemId, legacy)));

        return Collections.unmodifiableMap(reservations);
    }

    /** Returns every allocated identity that belongs to one configured block item. */
    public static synchronized Map<String, JsonObject> identitiesForOwner(String ownerId) {
        load();
        return identitiesForOwner(allocations(), ownerId);
    }

    public static synchronized Map<String, JsonObject> identitiesForOwner(String ownerId,
                                                                            Map<String, Integer> reservations) {
        load();
        return identitiesForOwner(stageReservations(allocations(), reservations), ownerId);
    }

    static Map<String, JsonObject> identitiesForOwner(Map<String, JsonObject> allocations, String ownerId) {
        Map<String, JsonObject> identities = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> allocation : allocations.entrySet()) {
            JsonObject entry = allocation.getValue();
            String owner = entry.has("owner") ? entry.get("owner").getAsString()
                    : allocation.getKey().equals(ownerId) ? ownerId : legacyOwner(allocation.getKey());
            if (ownerId.equals(owner))
                identities.put(allocation.getKey(), entry.deepCopy());
        }
        return identities;
    }

    private static Map<String, JsonObject> stageReservations(Map<String, JsonObject> allocations,
                                                              Map<String, Integer> reservations) {
        Map<String, JsonObject> staged = new LinkedHashMap<>();
        allocations.forEach((itemId, entry) -> staged.put(itemId, entry.deepCopy()));
        reservations.forEach((itemId, variation) -> {
            int stagedVariation = allocateVariation(staged, itemId, variation);
            if (stagedVariation != variation)
                throw new IllegalStateException("Native block allocation changed while parsing " + itemId);
        });
        return staged;
    }

    private static String legacyOwner(String itemId) {
        for (NativeBlockRotation rotation : NativeBlockRotation.values()) {
            for (NativeBlockRotation.Variant variant : rotation.variants()) {
                String suffix = variant.suffix();
                if (!suffix.isEmpty() && itemId.endsWith(suffix))
                    return itemId.substring(0, itemId.length() - suffix.length());
            }
        }
        return itemId;
    }

    /**
     * Returns the physical block registry key saved for an allocation.
     * Older cache files used the logical key as the physical registry key.
     */
    public static String physicalBlockId(JsonObject entry, int variation) {
        if (entry.has("blockId")) return entry.get("blockId").getAsString();
        if (entry.has("key")) return entry.get("key").getAsString();
        return slotBlockId(variation);
    }

    public static String slotBlockId(int variation) {
        if (variation < 0 || variation >= SLOT_COUNT)
            throw new IllegalArgumentException("Invalid native block slot " + variation);
        return SLOT_BLOCK_PREFIX + variation;
    }

    public static boolean isValidLegacyVariation(Integer variation) {
        return variation != null && variation >= 0 && variation < SLOT_COUNT;
    }

    static int normalizeLightLevel(int light) {
        int normalized = Math.max(0, light);
        if (normalized > 15)
            throw new IllegalArgumentException("Block light must be between 0 and 15: " + light);
        return normalized;
    }

    /** Whether added server block states need more global palette bits than the vanilla client knows. */
    public static boolean blockPaletteNeedsRepack(int vanillaBlockStates, int currentBlockStates) {
        if (vanillaBlockStates < 1 || currentBlockStates < 1)
            throw new IllegalArgumentException("Block state counts must be positive");
        return paletteBits(currentBlockStates) > paletteBits(vanillaBlockStates);
    }

    private static int paletteBits(int blockStateCount) {
        return blockStateCount <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(blockStateCount - 1);
    }

    static int allocateVariation(Map<String, JsonObject> allocations, String itemId, Integer legacy) {
        boolean[] used = new boolean[SLOT_COUNT];
        for (Map.Entry<String, JsonObject> allocation : allocations.entrySet()) {
            if (!allocation.getKey().equals(itemId)
                    && NativeBlockStorage.key(allocation.getKey()).equals(NativeBlockStorage.key(itemId)))
                throw new IllegalArgumentException("Native block identity conflicts between " + itemId + " and " + allocation.getKey());
            int variation = allocation.getValue().get("variation").getAsInt();
            if (variation < 0 || variation >= used.length)
                throw new IllegalStateException("Invalid cached appearance variation " + variation + " for " + allocation.getKey());
            if (used[variation])
                throw new IllegalStateException("Duplicate cached appearance variation " + variation);
            used[variation] = true;
        }
        JsonObject previous = allocations.get(itemId);
        if (previous != null) return previous.get("variation").getAsInt();
        int selected = legacy != null ? legacy : 0;
        if (selected < 0 || selected >= used.length) selected = 0;
        if (legacy == null || used[selected]) {
            selected = 0;
            while (selected < used.length && used[selected]) selected++;
        }
        if (selected >= used.length)
            throw new IllegalArgumentException("No free appearance state for " + itemId + " at variation " + selected);
        JsonObject entry = new JsonObject();
        entry.addProperty("itemId", itemId);
        entry.addProperty("variation", selected);
        allocations.put(itemId, entry);
        return selected;
    }

    public record Registration(String itemId, String ownerId, BlockData appearance, int light) {
    }

    /** Stages a complete item identity set, or commits it immediately outside a reload. */
    public static synchronized void registerAll(Map<String, Integer> reservations, List<Registration> registrations) {
        load();
        Map<String, JsonObject> staged = stageRegistrations(allocations(), reservations, registrations);
        if (pendingEntries != null) {
            pendingEntries = staged;
            for (Registration registration : registrations)
                pendingRegistrations.put(registration.itemId(), registration);
            return;
        }

        commit(staged, toNmsRegistrations(staged, registrations));
    }

    static Map<String, JsonObject> stageRegistrations(Map<String, JsonObject> allocations,
                                                       Map<String, Integer> reservations,
                                                       List<Registration> registrations) {
        Map<String, JsonObject> staged = stageReservations(allocations, reservations);
        Set<String> registered = new HashSet<>();
        for (Registration registration : registrations) {
            if (!registered.add(registration.itemId()))
                throw new IllegalArgumentException("Duplicate native block registration " + registration.itemId());
            if (registration.ownerId() == null || registration.appearance() == null)
                throw new IllegalArgumentException("Native block registration is incomplete for " + registration.itemId());
            int light = normalizeLightLevel(registration.light());
            JsonObject previous = staged.get(registration.itemId());
            if (previous == null)
                throw new IllegalArgumentException("Native block was not reserved " + registration.itemId());
            int slot = previous.get("variation").getAsInt();
            String key = NativeBlockStorage.key(registration.itemId());
            JsonObject entry = previous.deepCopy();
            String blockId = physicalBlockId(entry, slot);
            entry.addProperty("owner", registration.ownerId());
            entry.addProperty("key", key);
            entry.addProperty("blockId", blockId);
            entry.addProperty("appearance", registration.appearance().getAsString());
            entry.addProperty("light", light);
            staged.put(registration.itemId(), entry);
        }
        for (String identity : reservations.keySet()) {
            if (!registered.contains(identity))
                throw new IllegalArgumentException("Reserved native block was not registered " + identity);
        }
        return staged;
    }

    private static List<NMSHandler.CustomBlockRegistration> toNmsRegistrations(
            Map<String, JsonObject> staged, List<Registration> registrations) {
        List<NMSHandler.CustomBlockRegistration> result = new java.util.ArrayList<>(registrations.size());
        for (Registration registration : registrations) {
            JsonObject entry = staged.get(registration.itemId());
            result.add(new NMSHandler.CustomBlockRegistration(entry.get("key").getAsString(),
                    entry.get("variation").getAsInt(), registration.appearance(), entry.get("light").getAsInt()));
        }
        return result;
    }

    private static void commit(Map<String, JsonObject> staged,
                               List<NMSHandler.CustomBlockRegistration> registrations) {
        staged.entrySet().removeIf(allocation -> !allocation.getValue().has("key"));
        Map<String, JsonObject> previous = copyEntries(entries);
        if (!staged.equals(entries)) writeCache(staged);
        try {
            if (!registrations.isEmpty()) NMSHandlers.getHandler().registerCustomBlocks(registrations);
        } catch (RuntimeException | Error exception) {
            try {
                if (!staged.equals(entries)) writeCache(previous);
            } catch (RuntimeException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            throw exception;
        }
        entries.clear();
        entries.putAll(staged);
    }

    public static synchronized void register(String itemId, BlockData appearance, int light) {
        register(itemId, itemId, appearance, light);
    }

    public static synchronized void register(String itemId, String ownerId, BlockData appearance, int light) {
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put(itemId, null);
        Map<String, Integer> reservation = reserveVariations(requested);
        registerAll(reservation, List.of(new Registration(itemId, ownerId, appearance, light)));
    }

    private static void writeCache(Map<String, JsonObject> allocations) {
        JsonArray blocks = new JsonArray();
        for (JsonObject value : allocations.values()) if (value.has("key")) blocks.add(value);
        JsonObject root = new JsonObject();
        root.add("blocks", blocks);
        try {
            Files.createDirectories(cache().getParent());
            Path temporary = cache().resolveSibling("native-blocks.json.tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, cache(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                Files.deleteIfExists(temporary);
                throw exception;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist native block identities", exception);
        }
    }
}
