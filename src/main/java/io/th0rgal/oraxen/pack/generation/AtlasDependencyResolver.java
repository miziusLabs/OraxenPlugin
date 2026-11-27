package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.th0rgal.oraxen.utils.VirtualFile;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Resolves the {@code model -> parent -> textures} dependency graph across a resource pack
 * to classify textures between the {@code blocks} atlas and the 1.21.11+ / 26.x {@code items} atlas,
 * and detects cross-atlas texture references on 3D models so they can be bridged into {@code blocks.json}.
 */
public final class AtlasDependencyResolver {

    public enum ModelRenderKind {
        /**
         * 2D layered item model (inherits from {@code builtin/generated}, {@code item/generated},
         * {@code item/handheld}, {@code item/template_spawn_egg}, etc. without 3D elements).
         */
        ITEM_2D,
        /**
         * 3D geometry model (declares {@code elements} or inherits from {@code block/*}).
         */
        BLOCK_OR_3D,
        /**
         * Special builtin entity model ({@code builtin/entity}).
         */
        BUILTIN_ENTITY
    }

    public record TextureRef(@NotNull String namespace, @NotNull String path) implements Comparable<TextureRef> {
        public TextureRef {
            namespace = (namespace == null || namespace.isBlank()) ? "minecraft" : namespace.trim();
            path = normalizeTexturePath(path);
        }

        public static @NotNull TextureRef parse(@NotNull String rawRef) {
            String trimmed = rawRef.trim();
            if (trimmed.contains(":")) {
                String ns = StringUtils.substringBefore(trimmed, ":");
                String p = StringUtils.substringAfter(trimmed, ":");
                return new TextureRef(ns.isEmpty() ? "minecraft" : ns, p);
            }
            return new TextureRef("minecraft", trimmed);
        }

        private static String normalizeTexturePath(String rawPath) {
            if (rawPath == null) return "";
            String cleaned = rawPath.trim();
            while (cleaned.startsWith("/")) {
                cleaned = cleaned.substring(1);
            }
            if (cleaned.startsWith("textures/")) {
                cleaned = cleaned.substring("textures/".length());
            }
            if (cleaned.endsWith(".png")) {
                cleaned = cleaned.substring(0, cleaned.length() - ".png".length());
            }
            return cleaned;
        }

        public @NotNull String toResourceLocation() {
            return namespace + ":" + path;
        }

        public @NotNull String toPackFilePath() {
            return "assets/" + namespace + "/textures/" + path + ".png";
        }

        @Override
        public int compareTo(@NotNull TextureRef other) {
            int nsCmp = this.namespace.compareTo(other.namespace);
            return nsCmp != 0 ? nsCmp : this.path.compareTo(other.path);
        }
    }

    public record ParsedModel(
            @NotNull String modelKey,
            @Nullable String parentKey,
            boolean hasElements,
            @NotNull Map<String, String> rawTextures
    ) {
    }

    public record AtlasResolutionResult(
            @NotNull Set<TextureRef> blockAtlasTextures,
            @NotNull Set<TextureRef> itemAtlasTextures,
            @NotNull Set<TextureRef> bridgedVanillaItemTextures,
            @NotNull Set<TextureRef> fontOnlyTextures,
            @NotNull Map<String, Set<TextureRef>> missingTexturesByModel
    ) {
    }

    private enum AtlasType {
        BLOCKS,
        ITEMS
    }

    private static final class TextureUsage {
        private boolean blockUsage;
        private boolean itemUsage;
        private boolean vanillaBlockDefault;
        private boolean vanillaItemDefault;
        private boolean blockShape;
    }

    private static final class TextureGroups {
        private final Map<TextureRef, TextureRef> roots = new HashMap<>();

        private void add(TextureRef texture) {
            roots.putIfAbsent(texture, texture);
        }

        private void connect(Collection<TextureRef> textures) {
            if (textures.isEmpty()) return;
            TextureRef first = textures.iterator().next();
            add(first);
            for (TextureRef texture : textures) {
                add(texture);
                union(first, texture);
            }
        }

        private TextureRef find(TextureRef texture) {
            TextureRef parent = roots.get(texture);
            if (parent == null) {
                add(texture);
                return texture;
            }
            if (parent.equals(texture)) return texture;
            TextureRef root = find(parent);
            roots.put(texture, root);
            return root;
        }

        private void union(TextureRef first, TextureRef second) {
            TextureRef firstRoot = find(first);
            TextureRef secondRoot = find(second);
            if (!firstRoot.equals(secondRoot)) roots.put(secondRoot, firstRoot);
        }

        private Set<TextureRef> textures() {
            return roots.keySet();
        }
    }

    private static final Set<String> ITEM_2D_ROOT_PARENTS = Set.of(
            "builtin/generated",
            "minecraft:builtin/generated",
            "item/generated",
            "minecraft:item/generated",
            "item/handheld",
            "minecraft:item/handheld",
            "item/handheld_rod",
            "minecraft:item/handheld_rod",
            "item/template_spawn_egg",
            "minecraft:item/template_spawn_egg",
            "item/template_music_disc",
            "minecraft:item/template_music_disc"
    );

    private static final Set<String> VANILLA_BLOCK_ATLAS_SINGLES = Set.of(
        "entity/bell/bell_body",
        "entity/enchanting_table_book",
        "entity/enchantment/enchanting_table_book"
    );

    private static final Set<String> VANILLA_ITEM_ATLAS_TEXTURES = Set.of(
            "trims/items/helmet_trim",
            "trims/items/chestplate_trim",
            "trims/items/leggings_trim",
            "trims/items/boots_trim"
    );

    public @NotNull AtlasResolutionResult resolve(
            @NotNull Collection<VirtualFile> files,
            @NotNull Set<TextureRef> explicitItemLayerTextures,
            @NotNull Set<String> malformedPaths
    ) {
        return resolve(files, explicitItemLayerTextures, malformedPaths, false);
    }

    public @NotNull AtlasResolutionResult resolve(
            @NotNull Collection<VirtualFile> files,
            @NotNull Set<TextureRef> explicitItemLayerTextures,
            @NotNull Set<String> malformedPaths,
            boolean splitItemAtlas
    ) {
        return resolve(files, explicitItemLayerTextures, malformedPaths, splitItemAtlas, true);
    }

    public @NotNull AtlasResolutionResult resolve(
            @NotNull Collection<VirtualFile> files,
            @NotNull Set<TextureRef> explicitItemLayerTextures,
            @NotNull Set<String> malformedPaths,
            boolean splitItemAtlas,
            boolean excludeUnreferencedTextures
    ) {
        Map<String, ParsedModel> modelsByKey = new LinkedHashMap<>();
        Set<TextureRef> allPackTextures = new LinkedHashSet<>();
        Set<TextureRef> excludedDirPackTextures = new LinkedHashSet<>();
        Set<TextureRef> fontTextures = new LinkedHashSet<>();
        Set<String> itemModelKeys = new LinkedHashSet<>();
        Set<String> blockModelKeys = new LinkedHashSet<>();
        indexPackAssets(files, malformedPaths, modelsByKey, allPackTextures, excludedDirPackTextures,
                fontTextures, itemModelKeys, blockModelKeys);

        TextureGroups textureGroups = new TextureGroups();
        Map<String, Set<TextureRef>> texturesByModel = new LinkedHashMap<>();
        Set<TextureRef> referencedTextures = new LinkedHashSet<>(explicitItemLayerTextures);
        for (TextureRef explicit : explicitItemLayerTextures) {
            textureGroups.add(explicit);
        }
        Map<String, Set<TextureRef>> missingTexturesByModel = new LinkedHashMap<>();
        for (ParsedModel model : modelsByKey.values()) {
            Set<TextureRef> modelTextures = resolveModelTextures(model.modelKey(), modelsByKey);
            modelTextures.removeIf(texture -> isIgnoredTexture(texture, malformedPaths));
            texturesByModel.put(model.modelKey(), modelTextures);
            textureGroups.connect(modelTextures);
            referencedTextures.addAll(modelTextures);
            for (TextureRef texture : modelTextures) {
                if (excludedDirPackTextures.contains(texture)) allPackTextures.add(texture);
                if (!allPackTextures.contains(texture) && !isLikelyVanillaTexture(texture)) {
                    missingTexturesByModel.computeIfAbsent(model.modelKey(), ignored -> new LinkedHashSet<>()).add(texture);
                }
            }
        }

        Map<TextureRef, TextureUsage> usageByGroup = new HashMap<>();
        for (Map.Entry<String, Set<TextureRef>> entry : texturesByModel.entrySet()) {
            String modelKey = entry.getKey();
            boolean usedByItem = itemModelKeys.contains(modelKey);
            boolean usedByBlock = blockModelKeys.contains(modelKey);
            boolean blockShape = classifyModel(modelKey, modelsByKey) != ModelRenderKind.ITEM_2D;
            for (TextureRef texture : entry.getValue()) {
                TextureUsage usage = usageByGroup.computeIfAbsent(textureGroups.find(texture), ignored -> new TextureUsage());
                usage.itemUsage |= usedByItem;
                usage.blockUsage |= usedByBlock;
                usage.blockShape |= blockShape;
            }
        }

        for (TextureRef texture : explicitItemLayerTextures) {
            usageByGroup.computeIfAbsent(textureGroups.find(texture), ignored -> new TextureUsage()).itemUsage = true;
        }
        for (TextureRef texture : textureGroups.textures()) {
            TextureUsage usage = usageByGroup.computeIfAbsent(textureGroups.find(texture), ignored -> new TextureUsage());
            if (isVanillaItemDefault(texture)) usage.vanillaItemDefault = true;
            else if (isVanillaBlockDefault(texture)) usage.vanillaBlockDefault = true;
        }

        Map<TextureRef, AtlasType> atlasByTexture = new HashMap<>();
        for (TextureRef texture : textureGroups.textures()) {
            TextureUsage usage = usageByGroup.get(textureGroups.find(texture));
            AtlasType atlas = usage.blockUsage ? AtlasType.BLOCKS
                    : usage.vanillaBlockDefault ? AtlasType.BLOCKS
                    : usage.itemUsage ? AtlasType.ITEMS
                    : usage.vanillaItemDefault ? AtlasType.ITEMS
                    : usage.blockShape ? AtlasType.BLOCKS
                    : AtlasType.ITEMS;
            atlasByTexture.put(texture, atlas);
        }

        for (TextureRef explicit : explicitItemLayerTextures) {
            if (excludedDirPackTextures.contains(explicit)) {
                allPackTextures.add(explicit);
            }
        }
        Set<TextureRef> bridgedVanillaItemTextures = new LinkedHashSet<>();
        Set<TextureRef> fontOnlyTextures = computeFontOnlyTextures(fontTextures, referencedTextures);
        Set<TextureRef> blockAtlasTextures = new LinkedHashSet<>();
        Set<TextureRef> itemAtlasTextures = new LinkedHashSet<>();
        Set<TextureRef> packTexturesToAtlas = new LinkedHashSet<>(allPackTextures);
        if (excludeUnreferencedTextures) packTexturesToAtlas.retainAll(referencedTextures);
        for (TextureRef texture : packTexturesToAtlas) {
            if (fontOnlyTextures.contains(texture)) continue;
            AtlasType atlas = atlasByTexture.getOrDefault(texture, defaultAtlas(texture));
            if (!splitItemAtlas || atlas == AtlasType.BLOCKS) blockAtlasTextures.add(texture);
            if (atlas == AtlasType.ITEMS) itemAtlasTextures.add(texture);
        }

        for (TextureRef texture : referencedTextures) {
            if (isVanillaItemFilterTarget(texture)
                    && atlasByTexture.get(textureGroups.find(texture)) == AtlasType.BLOCKS) {
                bridgedVanillaItemTextures.add(texture);
            }
        }
        blockAtlasTextures.addAll(bridgedVanillaItemTextures);

        return new AtlasResolutionResult(
                Collections.unmodifiableSet(blockAtlasTextures),
                Collections.unmodifiableSet(itemAtlasTextures),
                Collections.unmodifiableSet(bridgedVanillaItemTextures),
                Collections.unmodifiableSet(fontOnlyTextures),
                Collections.unmodifiableMap(missingTexturesByModel)
        );
    }

    private void indexPackAssets(
            @NotNull Collection<VirtualFile> files,
            @NotNull Set<String> malformedPaths,
            @NotNull Map<String, ParsedModel> modelsByKey,
            @NotNull Set<TextureRef> allPackTextures,
            @NotNull Set<TextureRef> excludedDirPackTextures,
            @NotNull Set<TextureRef> fontTextures,
            @NotNull Set<String> itemModelKeys,
            @NotNull Set<String> blockModelKeys
    ) {
        for (VirtualFile file : files) {
            String path = file.getPath();
            if (isAnyPackTextureFile(path)) {
                TextureRef ref = textureRefFromPackPath(path);
                if (ref != null && !isIgnoredTexture(ref, malformedPaths)) {
                    if (isDefaultExcludedTexturePath(path)) {
                        excludedDirPackTextures.add(ref);
                    } else {
                        allPackTextures.add(ref);
                    }
                }
            } else if (isFontFile(path)) {
                JsonObject fontJson = readJsonObjectNonDestructive(file);
                if (fontJson != null) {
                    collectFontTextures(fontJson, fontTextures);
                }
            } else if (isModelFile(path)) {
                String modelKey = modelKeyFromPackPath(path);
                JsonObject modelJson = readJsonObjectNonDestructive(file);
                if (modelKey != null && modelJson != null) {
                    modelsByKey.put(modelKey, parseModel(modelKey, modelJson));
                }
            } else if (isItemDefinitionFile(path)) {
                JsonObject itemJson = readJsonObjectNonDestructive(file);
                if (itemJson != null) collectItemModelReferences(itemJson, itemModelKeys);
            } else if (isBlockStateFile(path)) {
                JsonObject blockStateJson = readJsonObjectNonDestructive(file);
                if (blockStateJson != null) collectBlockModelReferences(blockStateJson, blockModelKeys);
            }
        }
    }

    private @NotNull Set<TextureRef> computeFontOnlyTextures(
            @NotNull Set<TextureRef> fontTextures,
            @NotNull Set<TextureRef> referencedTextures
    ) {
        Set<TextureRef> fontOnlyTextures = new LinkedHashSet<>();
        for (TextureRef fontTex : fontTextures) {
            if (!referencedTextures.contains(fontTex)) {
                fontOnlyTextures.add(fontTex);
            }
        }
        return fontOnlyTextures;
    }

    private static AtlasType defaultAtlas(TextureRef texture) {
        AtlasType vanillaDefault = vanillaAtlasDefault(texture);
        if (vanillaDefault != null) return vanillaDefault;
        return texture.path().startsWith("item/") ? AtlasType.ITEMS : AtlasType.BLOCKS;
    }

    private static boolean isVanillaItemDefault(TextureRef texture) {
        return vanillaAtlasDefault(texture) == AtlasType.ITEMS;
    }

    private static boolean isVanillaItemFilterTarget(TextureRef texture) {
        return "minecraft".equals(texture.namespace()) && texture.path().startsWith("item/");
    }

    private static boolean isVanillaBlockDefault(TextureRef texture) {
        return vanillaAtlasDefault(texture) == AtlasType.BLOCKS;
    }

    private static @Nullable AtlasType vanillaAtlasDefault(TextureRef texture) {
        if (!"minecraft".equals(texture.namespace())) return null;
        if (texture.path().startsWith("block/") || texture.path().startsWith("entity/conduit/")) {
            return AtlasType.BLOCKS;
        }
        if (VANILLA_BLOCK_ATLAS_SINGLES.contains(texture.path())) return AtlasType.BLOCKS;
        if (texture.path().startsWith("item/") || VANILLA_ITEM_ATLAS_TEXTURES.contains(texture.path())) {
            return AtlasType.ITEMS;
        }
        return null;
    }

    private static void collectItemModelReferences(JsonElement element, Set<String> modelKeys) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement type = object.get("type");
            JsonElement model = object.get("model");
            if (type != null && type.isJsonPrimitive() && model != null && model.isJsonPrimitive()
                    && model.getAsJsonPrimitive().isString()) {
                String typeName = type.getAsString();
                if ("model".equals(typeName) || "minecraft:model".equals(typeName)) {
                    modelKeys.add(normalizeModelKey(model.getAsString()));
                }
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collectItemModelReferences(entry.getValue(), modelKeys);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectItemModelReferences(child, modelKeys);
        }
    }

    private static void collectBlockModelReferences(JsonElement element, Set<String> modelKeys) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement model = object.get("model");
            if (model != null && model.isJsonPrimitive() && model.getAsJsonPrimitive().isString()) {
                modelKeys.add(normalizeModelKey(model.getAsString()));
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collectBlockModelReferences(entry.getValue(), modelKeys);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectBlockModelReferences(child, modelKeys);
        }
    }

    public @NotNull ModelRenderKind classifyModel(
            @NotNull String modelKey,
            @NotNull Map<String, ParsedModel> modelsByKey
    ) {
        Set<String> visited = new HashSet<>();
        String currentKey = normalizeModelKey(modelKey);

        while (currentKey != null && visited.add(currentKey)) {
            if ("builtin/entity".equals(currentKey) || "minecraft:builtin/entity".equals(currentKey)) {
                return ModelRenderKind.BUILTIN_ENTITY;
            }
            ParsedModel current = modelsByKey.get(currentKey);
            if (current != null && current.hasElements()) {
                return ModelRenderKind.BLOCK_OR_3D;
            }
            if (ITEM_2D_ROOT_PARENTS.contains(currentKey)) {
                return ModelRenderKind.ITEM_2D;
            }
            if (current == null) {
                if (currentKey.startsWith("minecraft:item/") || currentKey.startsWith("item/")) {
                    return ModelRenderKind.ITEM_2D;
                }
                return ModelRenderKind.BLOCK_OR_3D;
            }
            currentKey = current.parentKey() != null ? normalizeModelKey(current.parentKey()) : null;
        }

        return ModelRenderKind.BLOCK_OR_3D;
    }

    public @NotNull Set<TextureRef> resolveModelTextures(
            @NotNull String modelKey,
            @NotNull Map<String, ParsedModel> modelsByKey
    ) {
        Map<String, String> mergedTextureMap = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<ParsedModel> hierarchy = new ArrayDeque<>();

        String currentKey = normalizeModelKey(modelKey);
        while (currentKey != null && visited.add(currentKey)) {
            ParsedModel model = modelsByKey.get(currentKey);
            if (model == null) break;
            hierarchy.push(model);
            currentKey = model.parentKey() != null ? normalizeModelKey(model.parentKey()) : null;
        }

        // Apply from parent down to child so child overrides parent texture variables
        while (!hierarchy.isEmpty()) {
            ParsedModel layer = hierarchy.pop();
            mergedTextureMap.putAll(layer.rawTextures());
        }

        Set<TextureRef> resolved = new LinkedHashSet<>();
        for (String value : mergedTextureMap.values()) {
            String resolvedValue = resolveTextureVariable(value, mergedTextureMap);
            if (resolvedValue != null && !resolvedValue.startsWith("#")) {
                resolved.add(TextureRef.parse(resolvedValue));
            }
        }
        return resolved;
    }

    private static @Nullable String resolveTextureVariable(String rawValue, Map<String, String> textureMap) {
        String current = rawValue;
        int depth = 0;
        while (current != null && current.startsWith("#") && depth++ < 16) {
            current = textureMap.get(current.substring(1));
        }
        return current;
    }

    private static @NotNull ParsedModel parseModel(@NotNull String modelKey, @NotNull JsonObject json) {
        String parent = json.has("parent") && json.get("parent").isJsonPrimitive()
                ? json.get("parent").getAsString()
                : null;
        boolean hasElements = json.has("elements") && json.get("elements").isJsonArray()
                && !json.getAsJsonArray("elements").isEmpty();

        Map<String, String> rawTextures = new LinkedHashMap<>();
        if (json.has("textures") && json.get("textures").isJsonObject()) {
            JsonObject texObj = json.getAsJsonObject("textures");
            for (Map.Entry<String, JsonElement> entry : texObj.entrySet()) {
                JsonElement val = entry.getValue();
                if (val != null && val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()) {
                    rawTextures.put(entry.getKey(), val.getAsString());
                }
            }
        }
        return new ParsedModel(modelKey, parent, hasElements, rawTextures);
    }

    private static void collectFontTextures(@NotNull JsonObject fontJson, @NotNull Set<TextureRef> fontTextures) {
        if (!fontJson.has("providers") || !fontJson.get("providers").isJsonArray()) return;
        JsonArray providers = fontJson.getAsJsonArray("providers");
        for (JsonElement element : providers) {
            if (!element.isJsonObject()) continue;
            JsonObject provider = element.getAsJsonObject();
            if (provider.has("file") && provider.get("file").isJsonPrimitive()) {
                fontTextures.add(TextureRef.parse(provider.get("file").getAsString()));
            }
        }
    }

    private static boolean isAnyPackTextureFile(String path) {
        String[] parts = path.split("/");
        return parts.length > 3
                && "assets".equals(parts[0])
                && "textures".equals(parts[2])
                && path.endsWith(".png");
    }

    private static boolean isDefaultExcludedTexturePath(String path) {
        return path.endsWith("_layer_1.png")
                || path.endsWith("_layer_2.png")
                || path.contains("/textures/entity/equipment/")
                || path.contains("/textures/colormap/")
                || path.contains("/textures/gui/")
                || path.contains("/textures/environment/");
    }

    private static boolean isFontFile(String path) {
        return path.startsWith("assets/") && path.contains("/font/") && path.endsWith(".json");
    }

    private static boolean isModelFile(String path) {
        return path.startsWith("assets/") && path.contains("/models/") && path.endsWith(".json");
    }

    private static boolean isItemDefinitionFile(String path) {
        return path.startsWith("assets/") && path.contains("/items/") && path.endsWith(".json");
    }

    private static boolean isBlockStateFile(String path) {
        return path.startsWith("assets/") && path.contains("/blockstates/") && path.endsWith(".json");
    }

    private static @Nullable TextureRef textureRefFromPackPath(String path) {
        String namespace = StringUtils.substringBetween(path, "assets/", "/textures/");
        String relPath = StringUtils.substringAfter(path, "/textures/");
        if (namespace == null || relPath == null || relPath.isEmpty()) {
            return null;
        }
        return new TextureRef(namespace, relPath);
    }

    static @Nullable String modelKeyFromPackPath(String path) {
        String namespace = StringUtils.substringBetween(path, "assets/", "/models/");
        String relPath = StringUtils.substringAfter(path, "/models/");
        if (namespace == null || relPath == null || !relPath.endsWith(".json")) {
            return null;
        }
        String withoutExt = relPath.substring(0, relPath.length() - ".json".length());
        return namespace + ":" + withoutExt;
    }

    static @NotNull String normalizeModelKey(@NotNull String rawKey) {
        String trimmed = rawKey.trim();
        if (trimmed.endsWith(".json")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".json".length());
        }
        if (!trimmed.contains(":")) {
            return "minecraft:" + trimmed;
        }
        return trimmed;
    }

    private static boolean isIgnoredTexture(TextureRef ref, Set<String> malformedPaths) {
        if ("minecraft".equals(ref.namespace()) && "required/null".equals(ref.path())) {
            return true;
        }
        String fullPath = ref.toPackFilePath();
        return malformedPaths.stream().anyMatch(fullPath::startsWith);
    }

    private static boolean isLikelyVanillaTexture(TextureRef ref) {
        return "minecraft".equals(ref.namespace())
                && (ref.path().startsWith("block/")
                || ref.path().startsWith("item/")
                || ref.path().startsWith("entity/")
                || ref.path().startsWith("particle/"));
    }

    static @Nullable JsonObject readJsonObjectNonDestructive(VirtualFile file) {
        try {
            InputStream stream = file.getInputStream();
            if (stream == null) return null;
            byte[] bytes;
            try (stream) {
                bytes = stream.readAllBytes();
            }
            file.setInputStream(new ByteArrayInputStream(bytes));
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (content.isBlank()) return null;
            JsonElement parsed = JsonParser.parseString(content);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
