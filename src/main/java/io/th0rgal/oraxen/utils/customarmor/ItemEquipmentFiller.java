package io.th0rgal.oraxen.utils.customarmor;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.utils.VersionUtil;
import io.th0rgal.oraxen.utils.logs.Logs;
import net.kyori.adventure.key.Key;
import org.apache.commons.lang3.EnumUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ItemEquipmentFiller {

    private ItemEquipmentFiller() {
    }

    public static void prepare(ItemBuilder item, Material type, ConfigurationSection mergedSection) {
        ItemEquipment equipment = ItemEquipment.parse(mergedSection);
        item.setEquipment(equipment);
        item.deferEquipmentDefaults(null);
        if (equipment == null)
            return;

        CustomArmorType armorType = CustomArmorType.getSetting();
        Consumer<Collection<EquipmentLayer>> defaults;
        if (armorType == CustomArmorType.COMPONENT && VersionUtil.atOrAbove("1.21.2"))
            defaults = layers -> applyComponent(item, type, mergedSection, equipment, layers);
        else if (armorType == CustomArmorType.TRIMS)
            defaults = layers -> applyTrims(item, type, mergedSection, equipment, layers);
        else
            return;

        if (equipment.layers().isEmpty())
            item.deferEquipmentDefaults(defaults);
        else
            defaults.accept(equipment.layers().keySet());
    }

    public static void resolveSharedDefaults(Map<String, ItemBuilder> items) {
        Map<String, EquipmentSet> sets = EquipmentSets.collect(EquipmentSets.configuredEquipment(items), ignored -> {
        });
        items.forEach((itemId, item) -> {
            if (!item.hasEquipment())
                return;
            EquipmentSet set = sets.get(item.getEquipment().id());
            try {
                item.resolveEquipmentDefaults(set.layers().keySet());
            } catch (Exception exception) {
                Logs.logWarning("Failed to resolve equipment defaults for item \"" + itemId + "\".");
                Logs.debug(exception);
            }
        });
    }

    private static void applyComponent(ItemBuilder item, Material type, ConfigurationSection mergedSection,
            ItemEquipment equipment, Collection<EquipmentLayer> layers) {
        ConfigurationSection componentsSection = mergedSection.getConfigurationSection("components");
        ConfigurationSection equippableSection = componentsSection != null
                ? componentsSection.getConfigurationSection("equippable")
                : null;

        String explicitSlot = equippableSection != null ? equippableSection.getString("slot") : null;
        if (equippableSection != null && equippableSection.contains("slot")
                && (explicitSlot == null || EnumUtils.getEnum(EquipmentSlot.class, explicitSlot) == null))
            return;

        EquippableComponent component = item.getEquippableComponent();
        if (component == null) {
            try {
                component = new ItemStack(type).getItemMeta().getEquippable();
            } catch (NoSuchMethodError exception) {
                Logs.logWarning("Equippable components are not available on this server version.");
                Logs.debug(exception);
                return;
            }
        }
        if (component == null)
            return;

        String materialSlot = materialSlotName(type);
        String slotName = inferSlotName(explicitSlot, materialSlot, layers);
        if (slotName == null) {
            Logs.logError("Item \"" + mergedSection.getName()
                    + "\" has an equipment section but needs components.equippable.slot because no slot could be inferred.");
            item.setEquippableComponent(null);
            return;
        }
        EquipmentSlot slot = EnumUtils.getEnum(EquipmentSlot.class, slotName);
        if (slot == null) {
            item.setEquippableComponent(null);
            return;
        }
        component.setSlot(slot);

        boolean copiedVanillaDefaults = copyVanillaEquippableDefaults(component, type, equippableSection);
        if ((equippableSection == null || !equippableSection.contains("equip_on_interact")) && !copiedVanillaDefaults
                && (slotName.equals("BODY") || slotName.equals("SADDLE")))
            setEquipOnInteract(component, defaultEquipOnInteract(slotName, layers));
        if (equippableSection == null || !equippableSection.contains("model"))
            component.setModel(NamespacedKey.fromString(equipmentAssetKey(equipment.id())));
        if ((equippableSection == null || !equippableSection.contains("allowed_entity_types"))
                && (slotName.equals("BODY") || slotName.equals("SADDLE"))) {
            List<EntityType> entityTypes = new ArrayList<>();
            for (String entityTypeName : allowedEntityTypeNames(slotName, layers)) {
                EntityType entityType = EnumUtils.getEnum(EntityType.class, entityTypeName);
                if (entityType != null)
                    entityTypes.add(entityType);
            }
            if (!entityTypes.isEmpty())
                component.setAllowedEntities(entityTypes);
        }

        item.setEquippableComponent(component);
    }

    private static void applyTrims(ItemBuilder item, Material type, ConfigurationSection mergedSection,
            ItemEquipment equipment, Collection<EquipmentLayer> layers) {
        if (!usesTrimLayers(layers))
            return;

        if (!mergedSection.contains("trim_pattern"))
            item.setTrimPattern(Key.key("oraxen", equipment.id()));
        if (Tag.ITEMS_TRIMMABLE_ARMOR.isTagged(type) && !item.getItemFlags().contains(ItemFlag.HIDE_ARMOR_TRIM))
            item.addItemFlags(ItemFlag.HIDE_ARMOR_TRIM);

        String armorMaterial = Settings.CUSTOM_ARMOR_TRIMS_MATERIAL.toString().toUpperCase(Locale.ROOT);
        if (!Tag.ITEMS_TRIMMABLE_ARMOR.isTagged(type) || !type.name().toUpperCase(Locale.ROOT).startsWith(armorMaterial))
            Logs.logWarning("Item \"" + mergedSection.getName() + "\" uses equipment in TRIMS mode and needs a "
                    + armorMaterial + " armor material.");
    }

    public static boolean usesTrimLayers(Collection<EquipmentLayer> layers) {
        return layers.contains(EquipmentLayer.HUMANOID)
                || layers.contains(EquipmentLayer.HUMANOID_LEGGINGS);
    }

    private static String equipmentAssetKey(String equipmentId) {
        return "oraxen:" + equipmentId;
    }

    @Nullable
    private static String materialSlotName(Material type) {
        try {
            EquipmentSlot slot = type.getEquipmentSlot();
            return slot != null ? slot.name() : null;
        } catch (NoSuchMethodError exception) {
            return null;
        }
    }

    private static boolean copyVanillaEquippableDefaults(EquippableComponent component, Material type,
            @Nullable ConfigurationSection equippableSection) {
        io.papermc.paper.datacomponent.item.Equippable vanillaEquippable;
        try {
            vanillaEquippable = ItemStack.of(type).getData(DataComponentTypes.EQUIPPABLE);
        } catch (NoClassDefFoundError | NoSuchMethodError | IllegalArgumentException exception) {
            return false;
        }
        if (vanillaEquippable == null)
            return false;

        if (equippableSection == null || !equippableSection.contains("equip_sound"))
            copyEquipSound(component, vanillaEquippable);
        if (equippableSection == null || !equippableSection.contains("dispensable"))
            copyDispensable(component, vanillaEquippable);
        if (equippableSection == null || !equippableSection.contains("swappable"))
            copySwappable(component, vanillaEquippable);
        if (equippableSection == null || !equippableSection.contains("damage_on_hurt"))
            copyDamageOnHurt(component, vanillaEquippable);
        if (equippableSection == null || !equippableSection.contains("equip_on_interact"))
            copyEquipOnInteract(component, vanillaEquippable);
        if (equippableSection == null || !equippableSection.contains("camera_overlay"))
            copyCameraOverlay(component, vanillaEquippable);
        copyShearingSettings(component, vanillaEquippable);
        return true;
    }

    private static void copyEquipSound(EquippableComponent component,
            io.papermc.paper.datacomponent.item.Equippable vanillaEquippable) {
        try {
            Sound equipSound = Registry.SOUNDS.get(vanillaEquippable.equipSound());
            if (equipSound != null)
                component.setEquipSound(equipSound);
        } catch (NoClassDefFoundError | NoSuchMethodError | IllegalArgumentException exception) {
        }
    }

    private static void copyDispensable(EquippableComponent component,
            io.papermc.paper.datacomponent.item.Equippable vanillaEquippable) {
        try {
            component.setDispensable(vanillaEquippable.dispensable());
        } catch (NoClassDefFoundError | NoSuchMethodError exception) {
        }
    }

    private static void copySwappable(EquippableComponent component,
            io.papermc.paper.datacomponent.item.Equippable vanillaEquippable) {
        try {
            component.setSwappable(vanillaEquippable.swappable());
        } catch (NoClassDefFoundError | NoSuchMethodError exception) {
        }
    }

    private static void copyDamageOnHurt(EquippableComponent component,
            io.papermc.paper.datacomponent.item.Equippable vanillaEquippable) {
        try {
            component.setDamageOnHurt(vanillaEquippable.damageOnHurt());
        } catch (NoClassDefFoundError | NoSuchMethodError exception) {
        }
    }

    private static void copyEquipOnInteract(EquippableComponent component,
            io.papermc.paper.datacomponent.item.Equippable vanillaEquippable) {
        try {
            component.setEquipOnInteract(vanillaEquippable.equipOnInteract());
        } catch (NoClassDefFoundError | NoSuchMethodError exception) {
        }
    }

    private static void copyCameraOverlay(EquippableComponent component,
            io.papermc.paper.datacomponent.item.Equippable vanillaEquippable) {
        try {
            if (vanillaEquippable.cameraOverlay() != null)
                component.setCameraOverlay(NamespacedKey.fromString(vanillaEquippable.cameraOverlay().asString()));
        } catch (NoClassDefFoundError | NoSuchMethodError | IllegalArgumentException exception) {
        }
    }

    private static void copyShearingSettings(EquippableComponent component,
            io.papermc.paper.datacomponent.item.Equippable vanillaEquippable) {
        try {
            EquippableComponent.class.getMethod("setCanBeSheared", boolean.class)
                    .invoke(component, vanillaEquippable.canBeSheared());
            Sound shearingSound = Registry.SOUNDS.get(vanillaEquippable.shearSound());
            if (shearingSound != null)
                EquippableComponent.class.getMethod("setShearingSound", Sound.class)
                        .invoke(component, shearingSound);
        } catch (ReflectiveOperationException | NoClassDefFoundError | NoSuchMethodError
                | IllegalArgumentException exception) {
        }
    }

    private static void setEquipOnInteract(EquippableComponent component, boolean value) {
        try {
            component.setEquipOnInteract(value);
        } catch (NoClassDefFoundError | NoSuchMethodError exception) {
        }
    }

    public static boolean defaultEquipOnInteract(String slotName, Collection<EquipmentLayer> layers) {
        if (slotName.equals("SADDLE"))
            return true;
        return slotName.equals("BODY") && layers.contains(EquipmentLayer.HAPPY_GHAST_BODY);
    }

    @Nullable
    public static String inferSlotName(@Nullable String explicitSlot, @Nullable String materialSlotName,
            Collection<EquipmentLayer> layers) {
        if (explicitSlot != null && !explicitSlot.isBlank())
            return explicitSlot.toUpperCase(Locale.ROOT);
        if (materialSlotName != null && !materialSlotName.equals("HAND") && !materialSlotName.equals("OFF_HAND"))
            return materialSlotName;
        for (EquipmentLayer layer : layers)
            if (layer.impliedSlotName() != null)
                return layer.impliedSlotName();
        return null;
    }

    public static List<String> allowedEntityTypeNames(String slotName, Collection<EquipmentLayer> layers) {
        if (!slotName.equals("BODY") && !slotName.equals("SADDLE"))
            return List.of();
        Set<String> entityTypeNames = new LinkedHashSet<>();
        for (EquipmentLayer layer : layers)
            if (slotName.equals(layer.impliedSlotName()))
                entityTypeNames.addAll(layer.allowedEntityTypeNames());
        return List.copyOf(entityTypeNames);
    }
}
