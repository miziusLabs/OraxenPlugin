package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.pack.generation.AtlasDependencyResolver.AtlasResolutionResult;
import io.th0rgal.oraxen.pack.generation.AtlasDependencyResolver.TextureRef;
import io.th0rgal.oraxen.utils.PlatformCapabilities;
import io.th0rgal.oraxen.utils.VirtualFile;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Generates {@code assets/minecraft/atlases/blocks.json} (and {@code items.json} on 1.21.11+ / 26.x)
 * using {@link AtlasDependencyResolver} to classify 2D item vs 3D block/model textures, exclude
 * font-only sprite sheets, and bridge cross-atlas references on 3D models.
 */
public class AtlasGenerator {

    private static final AtlasDependencyResolver RESOLVER = new AtlasDependencyResolver();
    private static final List<String> VANILLA_BLOCK_DIRECTORIES = List.of("block", "entity/conduit");
    private static final List<String> VANILLA_ITEM_DIRECTORIES = List.of("item");

    public AtlasGenerator() {
    }

    public static void generateAtlasFile(List<VirtualFile> output, Set<String> malformedTextures) {
        boolean splitItemAtlas = false;
        try {
            splitItemAtlas = PlatformCapabilities.supportsSplitItemAtlas(PlatformCapabilities.currentVersion());
        } catch (Exception ignored) {
            // Bukkit may not be initialized in standalone unit tests
        }
        generateAtlasFiles(output, malformedTextures, splitItemAtlas);
    }

    public static void generateAtlasFiles(
            @NotNull List<VirtualFile> output,
            @NotNull Set<String> malformedTextures,
            boolean includeSplitItemAtlas
    ) {
        boolean debug = isDebugEnabled();
        boolean excludeMalformed = isExcludeMalformedEnabled();
        boolean excludeUnreferenced = isExcludeUnreferencedEnabled();
        String generationType = getAtlasGenerationType();

        if (debug) {
            Logs.logInfo("Generating atlas-file for Resource Pack");
        }
        if (excludeMalformed && !malformedTextures.isEmpty()) {
            Logs.logWarning("Attempting to exclude malformed textures from atlas-file");
        }

        Set<TextureRef> explicitItemTextures = collectExplicitItemLayerTextures();
        Set<TextureRef> textureInventory = collectTextureInventory(output);
        Set<String> effectiveMalformed = excludeMalformed ? malformedTextures : Collections.emptySet();

        if (excludeMalformed && !malformedTextures.isEmpty()) {
            for (VirtualFile vf : output) {
                String p = vf.getPath();
                if (p.endsWith(".png") && malformedTextures.stream().anyMatch(p::startsWith)) {
                    Logs.logWarning("Excluding malformed texture from atlas-file: <gold>" + p);
                }
            }
        }

        AtlasResolutionResult resolution = RESOLVER.resolve(
                output,
                explicitItemTextures,
                effectiveMalformed,
                includeSplitItemAtlas,
                excludeUnreferenced
        );

        JsonObject blocksAtlas = buildAtlasJson(
                resolution.blockAtlasTextures(),
                resolution.bridgedVanillaItemTextures(),
                textureInventory,
                generationType,
                includeSplitItemAtlas ? VANILLA_ITEM_DIRECTORIES : List.of()
        );
        // Run imported operations after generated defaults so their filters and aliases retain effect.
        for (VirtualFile file : output) {
            if (file.getPath().equals("assets/minecraft/atlases/blocks.json") && file.isJsonObject()) {
                blocksAtlas = PackAssetMerger.mergeJson(file.getPath(), blocksAtlas, file.toJsonObject());
            }
        }
        if (includeSplitItemAtlas) {
            addFilterSources(blocksAtlas.getAsJsonArray("sources"), resolution.itemAtlasTextures(), VANILLA_BLOCK_DIRECTORIES);
        }
        upsertVirtualFile(output, "assets/minecraft/atlases", "blocks.json", blocksAtlas.toString());

        if (includeSplitItemAtlas && (!resolution.itemAtlasTextures().isEmpty()
                || resolution.blockAtlasTextures().stream().anyMatch(texture -> isInDirectory(texture, VANILLA_ITEM_DIRECTORIES)))) {
            JsonObject itemsAtlas = buildAtlasJson(
                    resolution.itemAtlasTextures(),
                    Collections.emptySet(),
                    textureInventory,
                    generationType,
                    VANILLA_BLOCK_DIRECTORIES
            );
            for (VirtualFile file : output) {
                if (file.getPath().equals("assets/minecraft/atlases/items.json") && file.isJsonObject()) {
                    itemsAtlas = PackAssetMerger.mergeJson(file.getPath(), itemsAtlas, file.toJsonObject());
                }
            }
            addFilterSources(itemsAtlas.getAsJsonArray("sources"), resolution.blockAtlasTextures(), VANILLA_ITEM_DIRECTORIES);
            upsertVirtualFile(output, "assets/minecraft/atlases", "items.json", itemsAtlas.toString());
        }
    }

    /**
     * Merges a split {@code items.json} atlas back into {@code blocks.json} for multi-version targets
     * older than 1.21.11 that only read {@code assets/minecraft/atlases/blocks.json}.
     */
    public static @NotNull byte[] mergeSplitAtlasesForLegacyTarget(
            @NotNull byte[] blocksAtlasBytes,
            @Nullable byte[] itemsAtlasBytes
    ) {
        if (itemsAtlasBytes == null || itemsAtlasBytes.length == 0) {
            return blocksAtlasBytes;
        }
        try {
            JsonObject blocksJson = JsonParser.parseString(new String(blocksAtlasBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject itemsJson = JsonParser.parseString(new String(itemsAtlasBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray blocksSources = blocksJson.has("sources") ? blocksJson.getAsJsonArray("sources") : new JsonArray();
            JsonArray itemsSources = itemsJson.has("sources") ? itemsJson.getAsJsonArray("sources") : new JsonArray();
            // These atlases also contain imported operations that can restore sprites after a filter.
            blocksSources = PackAssetMerger.distinctEntries(
                    removeSplitFilters(blocksSources, itemsSources, VANILLA_BLOCK_DIRECTORIES),
                    removeSplitFilters(itemsSources, blocksSources, VANILLA_ITEM_DIRECTORIES), false);
            blocksJson.add("sources", blocksSources);
            return blocksJson.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return blocksAtlasBytes;
        }
    }

    private static @NotNull JsonObject buildAtlasJson(
            @NotNull Set<TextureRef> textures,
            @NotNull Set<TextureRef> forceSingleEntryTextures,
            @NotNull Set<TextureRef> textureInventory,
            @NotNull String generationType,
            @NotNull List<String> oppositeVanillaDirectories
    ) {
        JsonObject atlas = new JsonObject();
        JsonArray sources = new JsonArray();
        boolean directoryMode = "DIRECTORY".equalsIgnoreCase(generationType);

        List<TextureRef> sorted = new ArrayList<>(textures);
        Collections.sort(sorted);

        for (TextureRef ref : sorted) {
            JsonObject entry = new JsonObject();
            if (directoryMode && !forceSingleEntryTextures.contains(ref) && ref.path().contains("/")
                    && directoryContainsOnlyAtlasTextures(ref, textures, textureInventory, oppositeVanillaDirectories)) {
                String dir = StringUtils.substringBeforeLast(ref.path(), "/");
                entry.addProperty("type", "directory");
                entry.addProperty("source", dir);
                entry.addProperty("prefix", dir + "/");
            } else {
                entry.addProperty("type", "single");
                entry.addProperty("resource", ref.toResourceLocation());
                entry.addProperty("sprite", ref.toResourceLocation());
            }
            if (!sources.contains(entry)) {
                sources.add(entry);
            }
        }

        if (directoryMode) {
            removeChildEntriesInDirectoryAtlas(sources);
        }
        atlas.add("sources", sources);
        return atlas;
    }

    private static boolean directoryContainsOnlyAtlasTextures(
            TextureRef texture,
            Set<TextureRef> atlasTextures,
            Set<TextureRef> textureInventory,
            List<String> oppositeVanillaDirectories
    ) {
        String directory = StringUtils.substringBeforeLast(texture.path(), "/") + "/";
        for (String vanillaPath : oppositeVanillaDirectories) {
            String vanillaDirectory = vanillaPath + "/";
            if (directory.startsWith(vanillaDirectory) || vanillaDirectory.startsWith(directory)) return false;
        }
        for (TextureRef candidate : textureInventory) {
            if (candidate.path().startsWith(directory)
                    && !atlasTextures.contains(candidate)) return false;
        }
        return true;
    }

    private static Set<TextureRef> collectTextureInventory(Collection<VirtualFile> files) {
        Set<TextureRef> textures = new LinkedHashSet<>();
        for (VirtualFile file : files) {
            String path = file.getPath();
            if (!path.startsWith("assets/") || !path.contains("/textures/") || !path.endsWith(".png")) continue;
            String namespace = StringUtils.substringBetween(path, "assets/", "/textures/");
            String texturePath = StringUtils.removeEnd(StringUtils.substringAfter(path, "/textures/"), ".png");
            if (namespace != null && texturePath != null && !texturePath.isEmpty()) {
                textures.add(new TextureRef(namespace, texturePath));
            }
        }
        return textures;
    }

    private static boolean isInDirectory(TextureRef texture, List<String> directories) {
        return directories.stream().anyMatch(directory -> texture.path().startsWith(directory + "/"));
    }

    private static void addFilterSources(JsonArray sources, Set<TextureRef> assignedTextures, List<String> directories) {
        for (TextureRef texture : assignedTextures.stream().filter(ref -> isInDirectory(ref, directories)).sorted().toList()) {
            JsonObject filter = exactFilter(texture);
            sources.remove(filter);
            sources.add(filter);
        }
    }

    private static JsonObject exactFilter(TextureRef texture) {
        JsonObject pattern = new JsonObject();
        pattern.addProperty("namespace", "^" + Pattern.quote(texture.namespace()) + "$");
        pattern.addProperty("path", "^" + Pattern.quote(texture.path()) + "$");
        JsonObject filter = new JsonObject();
        filter.addProperty("type", "filter");
        filter.add("pattern", pattern);
        return filter;
    }

    private static JsonArray removeSplitFilters(JsonArray sources, JsonArray oppositeSources, List<String> directories) {
        Set<JsonElement> splitFilters = new HashSet<>();
        for (JsonElement oppositeSource : oppositeSources) {
            if (!oppositeSource.isJsonObject()) continue;
            JsonObject entry = oppositeSource.getAsJsonObject();
            if (!entry.has("type") || !isSourceType(entry.get("type"), "single")) continue;
            JsonElement resource = entry.get("resource");
            if (resource == null || !resource.isJsonPrimitive() || !resource.equals(entry.get("sprite"))) continue;
            TextureRef texture = TextureRef.parse(resource.getAsString());
            if (isInDirectory(texture, directories)) splitFilters.add(exactFilter(texture));
        }
        JsonArray legacySources = new JsonArray();
        for (JsonElement source : sources) {
            if (!splitFilters.contains(source)) legacySources.add(source);
        }
        return legacySources;
    }

    private static boolean isSourceType(JsonElement type, String expected) {
        if (!type.isJsonPrimitive()) return false;
        String actual = type.getAsString();
        return expected.equals(actual) || ("minecraft:" + expected).equals(actual);
    }

    private static void removeChildEntriesInDirectoryAtlas(JsonArray atlasContent) {
        Set<JsonElement> remove = new HashSet<>();
        for (JsonElement element : atlasContent) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has("source")) continue;
                String parentDir = obj.get("source").getAsString();
                while (parentDir.contains("/")) {
                    parentDir = StringUtils.substringBeforeLast(parentDir, "/");
                    JsonObject parentObject = new JsonObject();
                    parentObject.addProperty("type", "directory");
                    parentObject.addProperty("source", parentDir);
                    parentObject.addProperty("prefix", directoryPath(parentDir) + "/");
                    if (atlasContent.contains(parentObject)) {
                        remove.add(element);
                        break;
                }
            }
        }
        remove.forEach(atlasContent::remove);
    }

    private static String directoryPath(String source) {
        return source.contains(":") ? StringUtils.substringAfter(source, ":") : source;
    }

    private static void upsertVirtualFile(List<VirtualFile> output, String folder, String name, String content) {
        VirtualFile file = new VirtualFile(
                folder,
                name,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))
        );
        output.removeIf(v -> v.getPath().equals(file.getPath()));
        output.add(file);
    }

    private static Set<TextureRef> collectExplicitItemLayerTextures() {
        Set<TextureRef> itemTextures = new LinkedHashSet<>();
        if (io.th0rgal.oraxen.OraxenPlugin.get() == null) {
            return itemTextures;
        }
        try {
            Collection<ItemBuilder> items = OraxenItems.getItems();
            if (items != null) {
                for (ItemBuilder builder : items) {
                    if (builder != null && builder.hasOraxenMeta() && builder.getOraxenMeta().hasLayers()) {
                        for (String layer : builder.getOraxenMeta().getLayers()) {
                            if (layer != null && !layer.isBlank()) {
                                itemTextures.add(TextureRef.parse(layer));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return itemTextures;
    }

    private static boolean isDebugEnabled() {
        if (io.th0rgal.oraxen.OraxenPlugin.get() == null) return false;
        try {
            return Settings.DEBUG.toBool();
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isExcludeMalformedEnabled() {
        if (io.th0rgal.oraxen.OraxenPlugin.get() == null) return true;
        try {
            return Settings.EXCLUDE_MALFORMED_ATLAS.toBool();
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean isExcludeUnreferencedEnabled() {
        if (io.th0rgal.oraxen.OraxenPlugin.get() == null) return true;
        try {
            return Settings.EXCLUDE_UNREFERENCED_ATLAS.toBool();
        } catch (Throwable e) {
            return true;
        }
    }

    private static String getAtlasGenerationType() {
        if (io.th0rgal.oraxen.OraxenPlugin.get() == null) return "SINGLE";
        try {
            String val = Settings.ATLAS_GENERATION_TYPE.toString();
            return val != null ? val : "SINGLE";
        } catch (Throwable e) {
            return "SINGLE";
        }
    }
}
