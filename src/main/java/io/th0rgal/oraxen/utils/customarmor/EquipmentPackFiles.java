package io.th0rgal.oraxen.utils.customarmor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.th0rgal.oraxen.pack.generation.PackFileCollector;
import io.th0rgal.oraxen.utils.SemanticServerVersion;
import io.th0rgal.oraxen.utils.VirtualFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class EquipmentPackFiles {

    private EquipmentPackFiles() {
    }

    public static Set<String> referencedTexturePaths(Map<String, EquipmentSet> equipmentSets) {
        Set<String> texturePaths = new LinkedHashSet<>();
        for (EquipmentSet equipmentSet : equipmentSets.values())
            for (String texture : equipmentSet.layers().values())
                texturePaths.add(PackFileCollector.modelPathToPackPath(texture));
        return texturePaths;
    }

    public static void generateComponentFiles(List<VirtualFile> output, Map<String, EquipmentSet> equipmentSets,
            SemanticServerVersion serverVersion, Consumer<String> warningConsumer, Consumer<String> errorConsumer,
            Consumer<String> debugConsumer) {
        List<String> unsupportedLayers = new ArrayList<>();
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
            boolean copiedLayer = false;
            for (Map.Entry<EquipmentLayer, String> layerEntry : equipmentSet.layers().entrySet()) {
                EquipmentLayer layer = layerEntry.getKey();
                if (!layer.isSupported(serverVersion)) {
                    unsupportedLayers.add(equipmentSet.id() + " (" + layer.configKey() + ")");
                    continue;
                }

                String targetPath = "assets/oraxen/textures/entity/equipment/" + layer.configKey() + "/"
                        + equipmentSet.id() + ".png";
                if (!copyLayerTexture(output, equipmentSet, layer, targetPath, errorConsumer))
                    continue;

                JsonObject textureObject = new JsonObject();
                textureObject.addProperty("texture", "oraxen:" + equipmentSet.id());
                JsonArray layerArray = new JsonArray();
                layerArray.add(textureObject);
                layersJson.add(layer.configKey(), layerArray);
                copiedLayer = true;
            }

            if (!copiedLayer)
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
                copyLayerTexture(output, equipmentSet, layer, targetPath, errorConsumer);
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

    private static boolean copyLayerTexture(List<VirtualFile> output, EquipmentSet equipmentSet, EquipmentLayer layer,
            String targetPath, Consumer<String> errorConsumer) {
        String configuredTexture = equipmentSet.layers().get(layer);
        String sourcePath = PackFileCollector.modelPathToPackPath(configuredTexture);
        VirtualFile source = findFile(output, sourcePath);
        if (source == null) {
            errorConsumer.accept("Missing equipment texture for item \"" + equipmentSet.layerItems().get(layer)
                    + "\", layer \"" + layer.configKey() + "\": \"" + configuredTexture + "\" resolved to "
                    + sourcePath + ".");
            return false;
        }

        if (sourcePath.equals(targetPath) || hasFile(output, targetPath))
            return true;

        try {
            output.add(copyVirtualFile(source, targetPath));
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

    private static boolean hasFile(List<VirtualFile> output, String path) {
        return findFile(output, path) != null;
    }

    private static VirtualFile findFile(List<VirtualFile> output, String path) {
        for (VirtualFile virtualFile : output)
            if (virtualFile.getPath().equals(path))
                return virtualFile;
        return null;
    }
}
