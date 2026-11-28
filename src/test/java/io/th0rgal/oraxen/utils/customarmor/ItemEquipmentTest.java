package io.th0rgal.oraxen.utils.customarmor;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemEquipmentTest {

    @Test
    void parsesDefaultIdAndStripsTextureExtension() {
        ConfigurationSection section = load("""
                forest_helmet:
                  equipment:
                    humanoid: default/armors/forest_armor_layer_1.png
                    humanoid_leggings: default/armors/forest_armor_layer_2
                """);

        ItemEquipment equipment = ItemEquipment.parse(section, new ArrayList<>()::add);

        assertNotNull(equipment);
        assertEquals("forest_helmet", equipment.id());
        assertEquals(Map.of(
                EquipmentLayer.HUMANOID, "default/armors/forest_armor_layer_1",
                EquipmentLayer.HUMANOID_LEGGINGS, "default/armors/forest_armor_layer_2"), equipment.layers());
    }

    @Test
    void rejectsNamespacedIdAndSkipsUnknownLayers() {
        ConfigurationSection section = load("""
                forest_helmet:
                  equipment:
                    id: oraxen:forest
                    unknown_layer: default/armors/forest
                """);
        List<String> warnings = new ArrayList<>();

        assertNull(ItemEquipment.parse(section, warnings::add));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("oraxen:forest")));

        ConfigurationSection valid = load("""
                forest_helmet:
                  equipment:
                    id: forest
                    unknown_layer: default/armors/forest
                """);
        warnings.clear();
        ItemEquipment equipment = ItemEquipment.parse(valid, warnings::add);

        assertNotNull(equipment);
        assertTrue(equipment.layers().isEmpty());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("unknown_layer")));
    }

    private static ConfigurationSection load(String yaml) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(yaml);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return configuration.getConfigurationSection("forest_helmet");
    }
}
