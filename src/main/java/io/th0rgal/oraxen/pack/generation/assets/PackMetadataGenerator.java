package io.th0rgal.oraxen.pack.generation.assets;

import com.google.gson.*;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.pack.generation.PackMcmetaUtils;
import io.th0rgal.oraxen.pack.generation.ShaderOverlay;
import io.th0rgal.oraxen.pack.generation.TextShaderGenerator;
import io.th0rgal.oraxen.utils.logs.Logs;
import io.th0rgal.oraxen.utils.ResourcePackFormatUtil;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Maintains pack metadata and generated shader overlay compatibility ranges. */
public final class PackMetadataGenerator {
    private static final Set<String> LEGACY_SHADER_OVERLAY_DIRECTORIES = Set.of("overlay_1_21_6_plus", "overlay_1_21_4_5");
    private final File packFolder;
    private final TextShaderGenerator textShaderGenerator;

    public PackMetadataGenerator(File packFolder, TextShaderGenerator textShaderGenerator) {
        this.packFolder = packFolder;
        this.textShaderGenerator = textShaderGenerator;
    }

    public void updatePackMcmeta() {
        if (isMcmetaGenerationDisabled()) {
            return;
        }

        Path mcmetaPath = packFolder.toPath().resolve("pack.mcmeta");
        if (!mcmetaPath.toFile().exists())
            return;

        PackMcmetaUtils.updatePackMcmetaFile(mcmetaPath);
    }

    /**
     * Updates pack.mcmeta to add shader overlay entries after shaders have been generated.
     * This must be called after {@link #generateFont()} to ensure overlay directories exist.
     */
    public void updatePackMcmetaOverlays() {
        if (isMcmetaGenerationDisabled()) {
            return;
        }

        Path mcmetaPath = packFolder.toPath().resolve("pack.mcmeta");
        if (!mcmetaPath.toFile().exists()) {
            return;
        }

        try {
            String content = Files.readString(mcmetaPath, StandardCharsets.UTF_8);
            JsonObject root;
            try {
                root = JsonParser.parseString(content).getAsJsonObject();
            } catch (Exception ignored) {
                Logs.logWarning("Failed to parse pack.mcmeta for overlay update");
                return;
            }

            boolean overlaysChanged = addShaderOverlayEntries(root);

            if (!overlaysChanged) {
                return;
            }

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(mcmetaPath, gson.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (Settings.DEBUG.toBool())
                e.printStackTrace();
            Logs.logWarning("Failed to update pack.mcmeta with shader overlays. Keeping existing file.");
        }
    }

    /**
     * Adds overlay entries to pack.mcmeta for cross-version shader compatibility.
     *
     * <p>Overlays allow the pack to include different shader formats for different
     * Minecraft client versions. The client automatically selects the appropriate
     * overlay based on its pack_format.</p>
     *
     * <p>Also expands the base pack's supported format range to cover all overlay
     * format ranges, so that clients of any supported version see the pack as
     * compatible.</p>
     */
    private boolean addShaderOverlayEntries(JsonObject root) {
        JsonObject overlays;
        if (root.has("overlays")) {
            overlays = root.getAsJsonObject("overlays");
        } else {
            overlays = new JsonObject();
        }

        JsonArray entries;
        if (overlays.has("entries")) {
            entries = overlays.getAsJsonArray("entries");
        } else {
            entries = new JsonArray();
        }

        int originalEntriesSize = entries.size();
        Set<String> knownDirectories = new HashSet<>(java.util.Arrays.stream(ShaderOverlay.values())
                .map(ShaderOverlay::directory)
                .collect(java.util.stream.Collectors.toSet()));
        knownDirectories.addAll(LEGACY_SHADER_OVERLAY_DIRECTORIES);
        JsonArray filteredEntries = new JsonArray();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                filteredEntries.add(element);
                continue;
            }

            JsonObject existingEntry = element.getAsJsonObject();
            String directory = existingEntry.has("directory") ? existingEntry.get("directory").getAsString() : null;
            if (directory == null || !knownDirectories.contains(directory)) {
                filteredEntries.add(existingEntry);
            }
        }
        entries = filteredEntries;

        int filteredEntriesSize = entries.size();

        for (ShaderOverlay overlay : textShaderGenerator.getGeneratedOverlays()) {
            JsonObject entry = new JsonObject();
            JsonObject formats = new JsonObject();
            formats.addProperty("min_inclusive", overlay.minFormat());
            formats.addProperty("max_inclusive", overlay.maxFormat());
            entry.add("formats", formats);
            entry.addProperty("directory", overlay.directory());
            entry.addProperty("min_format", overlay.minFormat());
            entry.addProperty("max_format", overlay.maxFormat());
            entries.add(entry);
        }

        int newEntriesAdded = entries.size() - filteredEntriesSize;
        boolean entriesRemoved = filteredEntriesSize != originalEntriesSize;
        boolean entriesChanged = newEntriesAdded > 0 || entriesRemoved;
        if (!entriesChanged) {
            return false;
        }

        overlays.add("entries", entries);
        root.add("overlays", overlays);

        expandSupportedFormatsRange(root);

        if (Settings.DEBUG.toBool()) {
            if (newEntriesAdded > 0 && entriesRemoved) {
                Logs.logInfo("Refreshed shader overlay entries in pack.mcmeta");
            } else if (newEntriesAdded > 0) {
                Logs.logInfo("Added " + newEntriesAdded + " shader overlay entries to pack.mcmeta");
            } else {
                Logs.logInfo("Removed stale shader overlay entries from pack.mcmeta");
            }
        }

        return true;
    }

    /**
     * Expands the base pack's supported_formats / min_format / max_format range
     * to cover all overlay format ranges, ensuring that clients of any supported
     * version see the pack as compatible.
     *
     * <p>Ranges crossing resource pack format 65 include both metadata forms:
     * pre-1.21.9 clients read {@code supported_formats}, while newer clients read
     * {@code min_format}/{@code max_format}.</p>
     */
    private void expandSupportedFormatsRange(JsonObject root) {
        List<ShaderOverlay> overlayList = textShaderGenerator.getGeneratedOverlays();
        if (overlayList.isEmpty()) return;

        int minOverlayFormat = overlayList.stream()
                .mapToInt(ShaderOverlay::minFormat).min().orElse(Integer.MAX_VALUE);
        int maxOverlayFormat = overlayList.stream()
                .mapToInt(ShaderOverlay::maxFormat).max().orElse(0);

        JsonObject pack = root.has("pack") && root.get("pack").isJsonObject()
                ? root.getAsJsonObject("pack") : new JsonObject();

        int serverFormat = ResourcePackFormatUtil.getCurrentResourcePackFormat();
        ShaderOverlay serverOverlay = ShaderOverlay.forPackFormat(serverFormat);
        int serverGroupMax = serverOverlay != null ? serverOverlay.maxFormat() : serverFormat;
        int effectiveMin = Math.min(serverFormat, minOverlayFormat);
        int effectiveMax = Math.max(serverGroupMax, maxOverlayFormat);

        if (effectiveMax >= 65) {
            pack.addProperty("min_format", effectiveMin);
            pack.addProperty("max_format", effectiveMax);
        } else {
            pack.remove("min_format");
            pack.remove("max_format");
        }

        if (effectiveMin < 65 && effectiveMax >= 18) {
            JsonObject supportedFormats = new JsonObject();
            supportedFormats.addProperty("min_inclusive", effectiveMin);
            supportedFormats.addProperty("max_inclusive", effectiveMax);
            pack.add("supported_formats", supportedFormats);
        } else {
            pack.remove("supported_formats");
        }

        root.add("pack", pack);
    }

    public boolean isMcmetaGenerationDisabled() {
        return Boolean.TRUE.equals(Settings.DISABLE_MCMETA_GENERATION.getValue());
    }


}
