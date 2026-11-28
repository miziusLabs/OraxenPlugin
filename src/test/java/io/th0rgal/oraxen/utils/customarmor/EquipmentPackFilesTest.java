package io.th0rgal.oraxen.utils.customarmor;

import io.th0rgal.oraxen.utils.SemanticServerVersion;
import io.th0rgal.oraxen.utils.VirtualFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
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
    void leavesExistingEquipmentJsonAndSkipsCopies() throws Exception {
        List<VirtualFile> output = new ArrayList<>();
        output.add(new VirtualFile("assets/oraxen/equipment", "forest.json",
                new ByteArrayInputStream("{\"layers\":{}}".getBytes(StandardCharsets.UTF_8))));
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "forest_armor_layer_1.png",
                new ByteArrayInputStream("texture".getBytes(StandardCharsets.UTF_8))));
        Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        layers.put(EquipmentLayer.HUMANOID, "default/armors/forest_armor_layer_1");
        EquipmentSet set = new EquipmentSet("forest", layers, List.of("forest_helmet"),
                Map.of(EquipmentLayer.HUMANOID, "forest_helmet"));
        List<String> debug = new ArrayList<>();

        EquipmentPackFiles.generateComponentFiles(output, Map.of("forest", set), SemanticServerVersion.parse("1.21.4"),
                new ArrayList<>()::add, new ArrayList<>()::add, debug::add);

        VirtualFile equipmentJson = file(output, "assets/oraxen/equipment/forest.json");
        assertNotNull(equipmentJson);
        assertEquals("{\"layers\":{}}", new String(equipmentJson.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertNull(file(output, "assets/oraxen/textures/entity/equipment/humanoid/forest.png"));
        assertFalse(debug.isEmpty());
    }

    @Test
    void explicitAmethystEquipmentAndLegacyTextureCoexist() throws Exception {
        byte[] textureBytes = "amethyst-texture".getBytes(StandardCharsets.UTF_8);
        List<VirtualFile> output = new ArrayList<>();
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "legacy_armor_layer_1.png",
                new ByteArrayInputStream(textureBytes)));
        Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        layers.put(EquipmentLayer.HUMANOID, "default/armors/legacy_armor_layer_1");
        EquipmentSet set = new EquipmentSet("amethyst", layers, List.of("amethyst_helmet"),
                Map.of(EquipmentLayer.HUMANOID, "amethyst_helmet"));

        new ComponentArmorModels().generatePackFiles(output, Map.of("amethyst", set), new HashSet<>(), new HashSet<>(),
                SemanticServerVersion.parse("1.21.4"));

        assertNotNull(file(output, "assets/oraxen/equipment/amethyst.json"));
        assertNotNull(file(output, "assets/oraxen/equipment/legacy.json"));
        assertArrayEquals(textureBytes, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid/amethyst.png").getInputStream().readAllBytes());
        assertArrayEquals(textureBytes, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid/legacy.png").getInputStream().readAllBytes());
        VirtualFile original = file(output, "assets/minecraft/textures/default/armors/legacy_armor_layer_1.png");
        assertNotNull(original);
        assertArrayEquals(textureBytes, original.getInputStream().readAllBytes());
    }

    @Test
    void preservesImportedTextureReferencedByEquipmentJson() throws Exception {
        byte[] texture = "knight-texture".getBytes(StandardCharsets.UTF_8);
        byte[] definition = "{\"layers\":{\"humanoid\":[{\"texture\":\"demo:knight_armor_layer_1\"}]}}"
                .getBytes(StandardCharsets.UTF_8);
        List<VirtualFile> output = new ArrayList<>();
        String folder = "assets/demo/textures/entity/equipment/humanoid";
        String name = "knight_armor_layer_1.png";
        output.add(new VirtualFile(folder, name, new ByteArrayInputStream(texture)));
        output.add(new VirtualFile("assets/demo/equipment", "knight.json", new ByteArrayInputStream(definition)));

        new ComponentArmorModels().generatePackFiles(output, Map.of(), new HashSet<>(), new HashSet<>(),
                SemanticServerVersion.parse("1.21.4"));

        VirtualFile original = file(output, folder + "/" + name);
        assertNotNull(original);
        assertArrayEquals(texture, original.getInputStream().readAllBytes());
        assertArrayEquals(definition, file(output, "assets/demo/equipment/knight.json").getInputStream().readAllBytes());
        assertArrayEquals(texture, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid/knight.png").getInputStream().readAllBytes());
    }

    @Test
    void legacyDetectionIgnoresGeneratedEquipmentCopies() {
        List<VirtualFile> output = new ArrayList<>();
        output.add(new VirtualFile("assets/oraxen/textures/entity/equipment/wings", "magic_elytra.png",
                new ByteArrayInputStream(new byte[0])));

        assertTrue(ComponentArmorModels.elytraPrefixes(output, Set.of("magic_elytra")).isEmpty());
    }

    private static VirtualFile file(List<VirtualFile> output, String path) {
        return output.stream().filter(file -> file.getPath().equals(path)).findFirst().orElse(null);
    }
}
