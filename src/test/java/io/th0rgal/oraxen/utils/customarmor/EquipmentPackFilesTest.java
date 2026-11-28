package io.th0rgal.oraxen.utils.customarmor;

import io.th0rgal.oraxen.utils.SemanticServerVersion;
import io.th0rgal.oraxen.utils.VirtualFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentPackFilesTest {

    @Test
    void writesEquipmentJsonAndCopiesReferencedTextures() throws Exception {
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);
        List<VirtualFile> output = new ArrayList<>();
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "forest_armor_layer_1.png",
                new ByteArrayInputStream(first)));
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "forest_armor_layer_2.png",
                new ByteArrayInputStream(second)));
        Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        layers.put(EquipmentLayer.HUMANOID, "default/armors/forest_armor_layer_1");
        layers.put(EquipmentLayer.HUMANOID_LEGGINGS, "default/armors/forest_armor_layer_2");
        EquipmentSet set = new EquipmentSet("forest", layers, List.of("forest_helmet"),
                Map.of(EquipmentLayer.HUMANOID, "forest_helmet", EquipmentLayer.HUMANOID_LEGGINGS, "forest_helmet"));

        EquipmentPackFiles.generateComponentFiles(output, Map.of("forest", set), SemanticServerVersion.parse("1.21.4"),
                new ArrayList<>()::add, new ArrayList<>()::add, new ArrayList<>()::add);

        VirtualFile equipmentJson = file(output, "assets/oraxen/equipment/forest.json");
        assertNotNull(equipmentJson);
        String json = new String(equipmentJson.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"humanoid\":[{\"texture\":\"oraxen:forest\"}]"));
        assertTrue(json.contains("\"humanoid_leggings\":[{\"texture\":\"oraxen:forest\"}]"));
        assertArrayEquals(first, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid/forest.png").getInputStream().readAllBytes());
        assertArrayEquals(second, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid_leggings/forest.png").getInputStream().readAllBytes());
        assertNotNull(file(output, "assets/minecraft/textures/default/armors/forest_armor_layer_1.png"));
        assertArrayEquals(first, file(output,
                "assets/minecraft/textures/default/armors/forest_armor_layer_1.png").getInputStream().readAllBytes());
    }

    @Test
    void leavesExistingEquipmentJsonAndSkipsCopies() {
        List<VirtualFile> output = new ArrayList<>();
        output.add(new VirtualFile("assets/oraxen/equipment", "forest.json",
                new ByteArrayInputStream("custom".getBytes(StandardCharsets.UTF_8))));
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "forest_armor_layer_1.png",
                new ByteArrayInputStream("texture".getBytes(StandardCharsets.UTF_8))));
        Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        layers.put(EquipmentLayer.HUMANOID, "default/armors/forest_armor_layer_1");
        EquipmentSet set = new EquipmentSet("forest", layers, List.of("forest_helmet"),
                Map.of(EquipmentLayer.HUMANOID, "forest_helmet"));
        List<String> debug = new ArrayList<>();

        EquipmentPackFiles.generateComponentFiles(output, Map.of("forest", set), SemanticServerVersion.parse("1.21.4"),
                new ArrayList<>()::add, new ArrayList<>()::add, debug::add);

        assertNull(file(output, "assets/oraxen/textures/entity/equipment/humanoid/forest.png"));
        assertFalse(debug.isEmpty());
    }

    @Test
    void legacyDetectionIgnoresReferencedTexturesAndEquipmentPrefixes() {
        List<VirtualFile> output = new ArrayList<>();
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "forest_armor_layer_1.png",
                new ByteArrayInputStream(new byte[0])));
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "legacy_armor_layer_1.png",
                new ByteArrayInputStream(new byte[0])));
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "forest_armor_layer_2.png",
                new ByteArrayInputStream(new byte[0])));
        Set<String> referenced = Set.of("assets/minecraft/textures/default/armors/forest_armor_layer_1.png");

        Set<String> prefixes = ComponentArmorModels.armorPrefixes(output, referenced, Set.of("other"));

        assertEquals(Set.of("legacy", "forest"), prefixes);
        assertEquals(Set.of("legacy"), ComponentArmorModels.armorPrefixes(output, referenced, Set.of("forest")));
        assertTrue(ComponentArmorModels.armorPrefixes(output, referenced, Set.of("legacy", "forest")).isEmpty());
    }

    @Test
    void legacyDetectionIgnoresGeneratedEquipmentCopies() {
        List<VirtualFile> output = new ArrayList<>();
        output.add(new VirtualFile("assets/oraxen/textures/entity/equipment/wings", "magic_elytra.png",
                new ByteArrayInputStream(new byte[0])));

        assertTrue(ComponentArmorModels.elytraPrefixes(output, Set.of(), Set.of("magic_elytra")).isEmpty());
    }

    private static VirtualFile file(List<VirtualFile> output, String path) {
        return output.stream().filter(file -> file.getPath().equals(path)).findFirst().orElse(null);
    }
}
