package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import com.google.gson.*;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.nms.NMSHandlers;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable native block identities and stable appearance allocations.
 * The JSON file is part of world persistence and must accompany world backups.
 * It is not a disposable cache. Native registrations must exist before chunks load.
 */
public final class NativeBlockRegistry {
    private static final Map<String, JsonObject> entries = new LinkedHashMap<>();
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

    public static synchronized int variation(String itemId, Integer legacy) {
        load();
        return allocateVariation(entries, itemId, legacy);
    }

    static int allocateVariation(Map<String, JsonObject> allocations, String itemId, Integer legacy) {
        boolean[] used = new boolean[774];
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

    public static synchronized void register(String itemId, BlockData appearance, int light) {
        load();
        JsonObject entry = entries.get(itemId).deepCopy();
        String key = NativeBlockStorage.key(itemId);
        entry.addProperty("key", key);
        entry.addProperty("appearance", appearance.getAsString());
        entry.addProperty("light", Math.max(0, light));
        NMSHandlers.getHandler().registerCustomBlock(key, appearance, Math.max(0, light));
        entries.put(itemId, entry);
        JsonArray blocks = new JsonArray();
        for (JsonObject value : entries.values()) if (value.has("key")) blocks.add(value);
        JsonObject root = new JsonObject();
        root.add("blocks", blocks);
        try {
            Files.createDirectories(cache().getParent());
            Path temporary = cache().resolveSibling("native-blocks.json.tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
            Files.move(temporary, cache(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist native block identities", exception);
        }
    }
}
