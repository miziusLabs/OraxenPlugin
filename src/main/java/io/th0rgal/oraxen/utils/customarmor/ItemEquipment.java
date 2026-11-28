package io.th0rgal.oraxen.utils.customarmor;

import io.th0rgal.oraxen.utils.logs.Logs;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

public record ItemEquipment(String id, Map<EquipmentLayer, String> layers) {

    public ItemEquipment {
        layers = Collections.unmodifiableMap(new EnumMap<>(layers));
    }

    @Nullable
    public static ItemEquipment parse(ConfigurationSection itemSection) {
        return parse(itemSection, Logs::logWarning);
    }

    @Nullable
    public static ItemEquipment parse(ConfigurationSection itemSection, Consumer<String> warningConsumer) {
        if (itemSection == null || !itemSection.contains("equipment"))
            return null;

        ConfigurationSection equipmentSection = itemSection.getConfigurationSection("equipment");
        if (equipmentSection == null) {
            warningConsumer.accept("Invalid equipment section for item \"" + itemSection.getName() + "\"; ignoring it.");
            return null;
        }

        String id = equipmentSection.getString("id", itemSection.getName());
        if (id == null || !id.matches("[a-z0-9_.-]+")) {
            warningConsumer.accept("Invalid equipment id \"" + id + "\" for item \"" + itemSection.getName()
                    + "\"; use lowercase letters, numbers, dots, underscores or hyphens without a namespace.");
            return null;
        }

        Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        for (String key : equipmentSection.getKeys(false)) {
            if (key.equals("id"))
                continue;
            EquipmentLayer layer = EquipmentLayer.fromConfigKey(key);
            if (layer == null) {
                warningConsumer.accept("Unknown equipment layer \"" + key + "\" for item \"" + itemSection.getName()
                        + "\"; skipping it.");
                continue;
            }

            String texture = equipmentSection.getString(key);
            if (texture == null || texture.isBlank()) {
                warningConsumer.accept("Invalid empty equipment layer \"" + key + "\" for item \"" + itemSection.getName()
                        + "\"; skipping it.");
                continue;
            }
            texture = texture.trim();
            if (texture.endsWith(".png"))
                texture = texture.substring(0, texture.length() - 4);
            layers.put(layer, texture);
        }

        return new ItemEquipment(id, layers);
    }
}
