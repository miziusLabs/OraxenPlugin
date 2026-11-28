package io.th0rgal.oraxen.utils.customarmor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentSetsTest {

    @Test
    void keepsFirstConflictingLayerAndNamesBothItems() {
        Map<String, ItemEquipment> items = Map.of(
                "z_boots", equipment("amethyst", EquipmentLayer.HUMANOID, "textures/second"),
                "a_helmet", equipment("amethyst", EquipmentLayer.HUMANOID, "textures/first"),
                "m_chestplate", equipment("amethyst"));
        List<String> warnings = new ArrayList<>();

        Map<String, EquipmentSet> sets = EquipmentSets.collect(items, warnings::add);

        EquipmentSet set = sets.get("amethyst");
        assertNotNull(set);
        assertEquals("textures/first", set.layers().get(EquipmentLayer.HUMANOID));
        assertEquals(List.of("a_helmet", "m_chestplate", "z_boots"), set.itemIds());
        assertEquals("a_helmet", set.layerItems().get(EquipmentLayer.HUMANOID));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("a_helmet") && warning.contains("z_boots")));
    }

    @Test
    void warnsOnceForASetWithoutLayers() {
        Map<String, ItemEquipment> items = Map.of(
                "a_helmet", equipment("empty"),
                "b_boots", equipment("empty"));
        List<String> warnings = new ArrayList<>();

        EquipmentSets.collect(items, warnings::add);

        assertEquals(1, warnings.stream().filter(warning -> warning.contains("no layers")).count());
    }

    private static ItemEquipment equipment(String id, EquipmentLayer layer, String texture) {
        Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        layers.put(layer, texture);
        return new ItemEquipment(id, layers);
    }

    private static ItemEquipment equipment(String id) {
        return new ItemEquipment(id, new EnumMap<>(EquipmentLayer.class));
    }
}
