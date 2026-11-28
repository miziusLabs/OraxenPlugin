package io.th0rgal.oraxen.utils.customarmor;

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.utils.MinecraftVersion;
import io.th0rgal.oraxen.utils.VersionUtil;
import io.th0rgal.oraxen.utils.VirtualFile;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class ComponentArmorModels {
    public void generatePackFiles(List<VirtualFile> output) {
        Map<String, EquipmentSet> equipmentSets = EquipmentSets.collect(EquipmentSets.configuredEquipment(),
                Logs::logWarning);
        EquipmentPackFiles.generateComponentFiles(output, equipmentSets,
                MinecraftVersion.getCurrentVersion().toSemanticVersion(), Logs::logWarning, Logs::logError, Logs::debug);

        Set<String> equipmentTexturePaths = EquipmentPackFiles.referencedTexturePaths(equipmentSets);
        Set<String> equipmentIds = equipmentSets.keySet();
        Set<String> armorPrefixes = armorPrefixes(output, equipmentTexturePaths, equipmentIds);
        Set<String> elytraPrefixes = elytraPrefixes(output, equipmentTexturePaths, equipmentIds);
        writeArmorModels(output, armorPrefixes, elytraPrefixes);
        copyArmorLayerTextures(output, equipmentTexturePaths, equipmentIds);
        logLegacySets(armorPrefixes, elytraPrefixes);
        checkOraxenArmorItems(armorPrefixes, elytraPrefixes);
    }

    private void logLegacySets(Set<String> armorPrefixes, Set<String> elytraPrefixes) {
        Set<String> legacySets = new TreeSet<>(armorPrefixes);
        legacySets.addAll(elytraPrefixes);
        if (!legacySets.isEmpty())
            Logs.logInfo("Legacy custom armor sets detected by texture file name: " + String.join(", ", legacySets)
                    + ". Add an equipment section to their items to use explicit equipment assets.");
    }

    private void writeArmorModels(List<VirtualFile> output, Set<String> armorPrefixes, Set<String> elytraPrefixes) {
        for (String armorprefix : armorPrefixes) {
            JsonObject armorModel = new JsonObject();
            armorModel.addProperty("texture", "oraxen:" + armorprefix);
            JsonArray armorModelArray = new JsonArray();
            armorModelArray.add(armorModel);
            JsonObject equipmentLayers = new JsonObject();
            equipmentLayers.add("humanoid", armorModelArray);
            equipmentLayers.add("humanoid_leggings", armorModelArray);
            JsonObject equipmentModel = new JsonObject();
            equipmentModel.add("layers", equipmentLayers);

            InputStream equipmentStream = new ByteArrayInputStream(
                    equipmentModel.toString().getBytes(StandardCharsets.UTF_8));
            output.add(new VirtualFile(
                    VersionUtil.atOrAbove("1.21.4") ? "assets/oraxen/equipment" : "assets/oraxen/models/equipment",
                    armorprefix + ".json", equipmentStream));

            if (!elytraPrefixes.contains(armorprefix))
                continue;

            JsonObject elytraLayers = new JsonObject();
            elytraLayers.add("wings", armorModelArray);
            JsonObject elytraModel = new JsonObject();
            elytraModel.add("layers", elytraLayers);
            InputStream elytraStream = new ByteArrayInputStream(
                    elytraModel.toString().getBytes(StandardCharsets.UTF_8));
            output.add(new VirtualFile(
                    VersionUtil.atOrAbove("1.21.4") ? "assets/oraxen/equipment" : "assets/oraxen/models/equipment",
                    armorprefix + "_elytra.json", elytraStream));
        }
    }

    private void copyArmorLayerTextures(List<VirtualFile> output, Set<String> excludedTexturePaths,
            Set<String> equipmentIds) {
        for (VirtualFile virtualFile : output) {
            String path = virtualFile.getPath();
            if (excludedTexturePaths.contains(path))
                continue;
            String armorFolder = path.endsWith("_armor_layer_1.png") ? "humanoid"
                    : path.endsWith("_armor_layer_2.png") ? "humanoid_leggings"
                            : path.endsWith("_elytra.png") ? "wings" : "";
            String armorPrefix = armorPrefix(virtualFile);
            if (armorPrefix.isEmpty() || armorFolder.isEmpty() || equipmentIds.contains(armorPrefix)
                    || isGeneratedEquipmentTexture(path))
                continue;

            String armorPath = "assets/oraxen/textures/entity/equipment/%s/%s.png".formatted(armorFolder, armorPrefix);
            virtualFile.setPath(armorPath);
        }
    }

    private void checkOraxenArmorItems(Set<String> armorPrefixes, Set<String> elytraPrefixes) {
        // No need to log for all 4 armor pieces, so skip to minimise log spam
        List<String> skippedArmorType = new ArrayList<>();
        for (Map.Entry<String, ItemBuilder> entry : OraxenItems.getEntries()) {
            String itemId = entry.getKey();
            ItemBuilder itemBuilder = entry.getValue();
            ItemStack itemStack = itemBuilder.getReferenceClone();
            String armorPrefix = StringUtils.substringBeforeLast(itemId, "_");

            if (itemBuilder.hasEquipment() || itemStack == null || !itemStack.hasItemMeta())
                continue;

            EquipmentSlot slot = slotFromItem(itemId, itemStack);
            boolean isElytra = isElytraItem(itemId, itemStack);

            if (!armorPrefixes.contains(armorPrefix) || skippedArmorType.contains(armorPrefix) || slot == null)
                continue;

            if (!itemBuilder.hasEquippableComponent() || itemBuilder.getEquippableComponent().getModel() == null) {
                if (!Settings.CUSTOM_ARMOR_COMPONENT_ASSIGN.toBool()) {
                    Logs.logWarning("Item " + itemId + " does not have an equippable-component configured properly.");
                    Logs.logWarning("Oraxen has been configured to use Components for custom-armor due to "
                            + Settings.CUSTOM_ARMOR_TYPE.getPath() + " setting");
                    Logs.logWarning("Custom Armor will not work unless an equippable-component is set.", true);
                    skippedArmorType.add(armorPrefix);
                } else {
                    EquippableComponent component = Optional.ofNullable(itemBuilder.getEquippableComponent())
                            .orElse(new ItemStack(Material.PAPER).getItemMeta().getEquippable());
                    String modelId = isElytra && elytraPrefixes.contains(armorPrefix) ? armorPrefix + "_elytra"
                            : armorPrefix;
                    NamespacedKey modelKey = NamespacedKey.fromString("oraxen:" + modelId);
                    if (component.getModel() == null)
                        component.setModel(modelKey);
                    component.setSlot(slot);
                    itemBuilder.setEquippableComponent(component);

                    itemBuilder.save();
                    Logs.logWarning("Item " + itemId + " does not have an equippable-component set.");
                    Logs.logInfo("Configured Components.equippable.model to %s for %s".formatted(modelKey.toString(),
                            itemId), true);
                }
            }
        }
    }

    @Nullable
    private EquipmentSlot slotFromItem(String itemId, @Nullable ItemStack itemStack) {
        if (isElytraItem(itemId, itemStack))
            return EquipmentSlot.CHEST;
        return switch (StringUtils.substringAfterLast(itemId, "_").toUpperCase(Locale.ENGLISH)) {
            case "HELMET" -> EquipmentSlot.HEAD;
            case "CHESTPLATE" -> EquipmentSlot.CHEST;
            case "LEGGINGS" -> EquipmentSlot.LEGS;
            case "BOOTS" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    private boolean isElytraItem(String itemId, @Nullable ItemStack itemStack) {
        if (itemId.toLowerCase(Locale.ENGLISH).endsWith("_elytra"))
            return true;
        if (itemStack == null || !itemStack.hasItemMeta())
            return false;
        return itemStack.getType() == Material.ELYTRA || itemStack.getItemMeta().isGlider();
    }

    static Set<String> armorPrefixes(List<VirtualFile> output, Set<String> excludedTexturePaths,
            Set<String> equipmentIds) {
        return output.stream()
                .filter(file -> !isGeneratedEquipmentTexture(file.getPath()))
                .filter(file -> !excludedTexturePaths.contains(file.getPath()))
                .map(ComponentArmorModels::armorPrefix)
                .filter(StringUtils::isNotBlank)
                .filter(prefix -> !equipmentIds.contains(prefix))
                .collect(Collectors.toSet());
    }

    static Set<String> elytraPrefixes(List<VirtualFile> output, Set<String> excludedTexturePaths,
            Set<String> equipmentIds) {
        return output.stream()
                .filter(file -> !isGeneratedEquipmentTexture(file.getPath()))
                .filter(file -> file.getPath().endsWith("_elytra.png"))
                .filter(file -> !excludedTexturePaths.contains(file.getPath()))
                .map(ComponentArmorModels::armorPrefix)
                .filter(StringUtils::isNotBlank)
                .filter(prefix -> !equipmentIds.contains(prefix))
                .collect(Collectors.toSet());
    }

    private static boolean isGeneratedEquipmentTexture(String path) {
        return path.startsWith("assets/oraxen/textures/entity/equipment/");
    }

    private static String armorPrefix(VirtualFile virtualFile) {
        return virtualFile.getPath().endsWith("_armor_layer_1.png")
                ? StringUtils.substringAfterLast(
                        StringUtils.substringBefore(virtualFile.getPath(), "_armor_layer_1.png"), "/")
                : virtualFile.getPath().endsWith("_armor_layer_2.png")
                        ? StringUtils.substringAfterLast(
                                StringUtils.substringBefore(virtualFile.getPath(), "_armor_layer_2.png"), "/")
                        : virtualFile.getPath().endsWith("_elytra.png")
                                ? StringUtils.substringAfterLast(
                                        StringUtils.substringBefore(virtualFile.getPath(), "_elytra.png"), "/")
                        : "";
    }
}
