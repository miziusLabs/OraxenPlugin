package io.th0rgal.oraxen.utils.customarmor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.th0rgal.oraxen.pack.generation.PackFileCollector;
import io.th0rgal.oraxen.utils.SemanticServerVersion;
import io.th0rgal.oraxen.utils.VirtualFile;
import io.th0rgal.oraxen.utils.logs.Logs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class EquipmentPackFiles {

    private EquipmentPackFiles() {
    }

    public static void copyLegacyComponentTextures(List<VirtualFile> output) {
        copyLegacyTextures(output, false);
    }

    public static void copyLegacyTrimTextures(List<VirtualFile> output) {
        copyLegacyTextures(output, true);
    }

    private static void copyLegacyTextures(List<VirtualFile> output, boolean trims) {
        String generatedRoot = trims ? "assets/oraxen/textures/trims/models/armor/"
                : "assets/oraxen/textures/entity/equipment/";
        for (VirtualFile file : List.copyOf(output)) {
            String path = file.getPath();
            int start = path.startsWith("assets/") ? 0 : path.indexOf("/assets/") + 1;
            if (!path.startsWith("assets/", start) || path.startsWith(generatedRoot, start))
                continue;
            String suffix;
            EquipmentLayer layer;
            if (path.endsWith("_armor_layer_1.png")) {
                suffix = "_armor_layer_1.png";
                layer = EquipmentLayer.HUMANOID;
            } else if (path.endsWith("_armor_layer_2.png")) {
                suffix = "_armor_layer_2.png";
                layer = EquipmentLayer.HUMANOID_LEGGINGS;
            } else if (!trims && path.endsWith("_elytra.png")) {
                suffix = "_elytra.png";
                layer = EquipmentLayer.WINGS;
            } else {
                continue;
            }
            String texture = path.substring(path.lastIndexOf('/') + 1, path.length() - suffix.length());
            if (texture.isEmpty())
                continue;
            String target = trims ? generatedRoot + texture
                    + (layer == EquipmentLayer.HUMANOID_LEGGINGS ? "_leggings.png" : ".png")
                    : componentTexturePath(layer, texture);
            copyLegacyTexture(output, file, path.substring(0, start) + target);
        }
    }

    public static void generateComponentFiles(List<VirtualFile> output, Map<String, EquipmentSet> equipmentSets,
            SemanticServerVersion serverVersion, Set<String> legacyArmorPrefixes, Set<String> legacyElytraPrefixes,
            Consumer<String> warningConsumer, Consumer<String> errorConsumer, Consumer<String> debugConsumer) {
        List<String> unsupportedLayers = new ArrayList<>();
        Map<String, VirtualFile> sources = indexSources(output);
        String equipmentFolder = serverVersion.toCanonicalNamespace().isAtLeast(SemanticServerVersion.parse("1.21.4"))
                ? "assets/oraxen/equipment"
                : "assets/oraxen/models/equipment";

        for (EquipmentSet equipmentSet : equipmentSets.values()) {
            String equipmentPath = equipmentFolder + "/" + equipmentSet.id() + ".json";
            if (hasFile(output, equipmentPath)) {
                debugConsumer.accept("Equipment asset " + equipmentPath + " already exists; skipping equipment id "
                        + equipmentSet.id() + ".");
                continue;
            }

            JsonObject layersJson = new JsonObject();
            for (Map.Entry<EquipmentLayer, String> layerEntry : equipmentSet.layers().entrySet()) {
                EquipmentLayer layer = layerEntry.getKey();
                if (!layer.isSupported(serverVersion)) {
                    unsupportedLayers.add(equipmentSet.id() + " (" + layer.configKey() + ")");
                    continue;
                }

                String targetPath = componentTexturePath(layer, equipmentSet.id());
                if (!copyLayerTexture(output, sources, equipmentSet, layer, targetPath, errorConsumer))
                    continue;

                addComponentLayer(layersJson, layer, equipmentSet.id());
            }

            String id = equipmentSet.id();
            if (legacyArmorPrefixes.contains(id)) {
                addLegacyLayer(output, layersJson, EquipmentLayer.HUMANOID, id);
                addLegacyLayer(output, layersJson, EquipmentLayer.HUMANOID_LEGGINGS, id);
            }
            if (id.endsWith("_elytra")) {
                String prefix = id.substring(0, id.length() - "_elytra".length());
                if (legacyElytraPrefixes.contains(prefix))
                    addLegacyLayer(output, layersJson, EquipmentLayer.WINGS, prefix);
            }
            if (layersJson.size() == 0)
                continue;
            JsonObject equipmentJson = new JsonObject();
            equipmentJson.add("layers", layersJson);
            output.add(new VirtualFile(equipmentFolder, equipmentSet.id() + ".json",
                    new ByteArrayInputStream(equipmentJson.toString().getBytes(StandardCharsets.UTF_8))));
        }

        logUnsupportedLayers(unsupportedLayers, serverVersion, warningConsumer);
    }

    public static void copyTrimTextures(List<VirtualFile> output, Map<String, EquipmentSet> equipmentSets,
            SemanticServerVersion serverVersion, Consumer<String> infoConsumer, Consumer<String> errorConsumer) {
        List<String> unsupportedLayers = new ArrayList<>();
        Map<String, VirtualFile> sources = indexSources(output);
        for (EquipmentSet equipmentSet : equipmentSets.values()) {
            for (Map.Entry<EquipmentLayer, String> layerEntry : equipmentSet.layers().entrySet()) {
                EquipmentLayer layer = layerEntry.getKey();
                String targetPath;
                if (layer == EquipmentLayer.HUMANOID) {
                    targetPath = "assets/oraxen/textures/trims/models/armor/" + equipmentSet.id() + ".png";
                } else if (layer == EquipmentLayer.HUMANOID_LEGGINGS) {
                    targetPath = "assets/oraxen/textures/trims/models/armor/" + equipmentSet.id() + "_leggings.png";
                } else {
                    unsupportedLayers.add(equipmentSet.id() + " (" + layer.configKey() + ")");
                    continue;
                }
                copyLayerTexture(output, sources, equipmentSet, layer, targetPath, errorConsumer);
            }
        }

        if (!unsupportedLayers.isEmpty())
            infoConsumer.accept("Equipment layers other than humanoid and humanoid_leggings are not used in TRIMS mode: "
                    + String.join(", ", unsupportedLayers) + ".");
    }

    private static void logUnsupportedLayers(List<String> unsupportedLayers, SemanticServerVersion serverVersion,
            Consumer<String> warningConsumer) {
        if (!unsupportedLayers.isEmpty())
            warningConsumer.accept("Equipment layers are not supported on Minecraft " + serverVersion + ": "
                    + String.join(", ", unsupportedLayers) + ".");
    }

    private static boolean copyLayerTexture(List<VirtualFile> output, Map<String, VirtualFile> sources,
            EquipmentSet equipmentSet, EquipmentLayer layer, String targetPath, Consumer<String> errorConsumer) {
        String configuredTexture = equipmentSet.layers().get(layer);
        String sourcePath = PackFileCollector.modelPathToPackPath(configuredTexture);
        VirtualFile source = sources.get(sourcePath);
        if (source == null) {
            errorConsumer.accept("Missing equipment texture for item \"" + equipmentSet.layerItems().get(layer)
                    + "\", layer \"" + layer.configKey() + "\": \"" + configuredTexture + "\" resolved to "
                    + sourcePath + ".");
            return false;
        }

        if (sourcePath.equals(targetPath) && findFile(output, targetPath) == source)
            return true;

        try {
            copyTexture(output, source, sources.get(sourcePath + ".mcmeta"), targetPath);
            return true;
        } catch (IOException exception) {
            errorConsumer.accept("Missing equipment texture for item \"" + equipmentSet.layerItems().get(layer)
                    + "\", layer \"" + layer.configKey() + "\": \"" + configuredTexture + "\" resolved to "
                    + sourcePath + ".");
            return false;
        }
    }

    private static VirtualFile copyVirtualFile(VirtualFile source, String targetPath) throws IOException {
        InputStream inputStream = source.getInputStream();
        if (inputStream == null)
            throw new IOException("Texture stream is unavailable");
        byte[] bytes;
        try (inputStream) {
            bytes = inputStream.readAllBytes();
        }
        source.setInputStream(new ByteArrayInputStream(bytes));

        int lastSlash = targetPath.lastIndexOf('/');
        String parent = lastSlash < 0 ? "" : targetPath.substring(0, lastSlash);
        String name = lastSlash < 0 ? targetPath : targetPath.substring(lastSlash + 1);
        return new VirtualFile(parent, name, new ByteArrayInputStream(bytes));
    }

    static boolean hasFile(List<VirtualFile> output, String path) {
        return findFile(output, path) != null;
    }

    static void copyLegacyTexture(List<VirtualFile> output, VirtualFile source, String targetPath) {
        if (hasFile(output, targetPath))
            return;
        try {
            copyTexture(output, source, findFile(output, source.getPath() + ".mcmeta"), targetPath);
        } catch (IOException exception) {
            Logs.logError("Failed to copy legacy armor texture " + source.getPath() + ".");
            Logs.debug(exception);
        }
    }

    private static void copyTexture(List<VirtualFile> output, VirtualFile source, VirtualFile sourceMetadata,
            String targetPath) throws IOException {
        VirtualFile texture = copyVirtualFile(source, targetPath);
        String targetMetadataPath = targetPath + ".mcmeta";
        VirtualFile metadata = sourceMetadata == null ? null : copyVirtualFile(sourceMetadata, targetMetadataPath);
        output.removeIf(file -> file.getPath().equals(targetPath) || file.getPath().equals(targetMetadataPath));
        output.add(texture);
        if (metadata != null)
            output.add(metadata);
    }

    private static Map<String, VirtualFile> indexSources(List<VirtualFile> output) {
        Map<String, VirtualFile> sources = new HashMap<>();
        for (VirtualFile file : output)
            sources.putIfAbsent(file.getPath(), file);
        return sources;
    }

    private static String componentTexturePath(EquipmentLayer layer, String texture) {
        return "assets/oraxen/textures/entity/equipment/" + layer.configKey() + "/" + texture + ".png";
    }

    private static void addLegacyLayer(List<VirtualFile> output, JsonObject layers, EquipmentLayer layer,
            String texture) {
        if (!layers.has(layer.configKey()) && hasFile(output, componentTexturePath(layer, texture)))
            addComponentLayer(layers, layer, texture);
    }

    private static void addComponentLayer(JsonObject layers, EquipmentLayer layer, String texture) {
        JsonObject layerJson = new JsonObject();
        layerJson.addProperty("texture", "oraxen:" + texture);
        JsonArray entries = new JsonArray();
        entries.add(layerJson);
        layers.add(layer.configKey(), entries);
    }

    private static VirtualFile findFile(List<VirtualFile> output, String path) {
        for (VirtualFile virtualFile : output)
            if (virtualFile.getPath().equals(path))
                return virtualFile;
        return null;
    }
}
