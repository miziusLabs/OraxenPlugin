package io.th0rgal.oraxen.utils.customarmor;

import io.th0rgal.oraxen.utils.SemanticServerVersion;
import io.th0rgal.oraxen.utils.VirtualFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "amethyst_armor_layer_1.png",
                new ByteArrayInputStream(first)));
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "amethyst_armor_layer_2.png",
                new ByteArrayInputStream(second)));
        Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        layers.put(EquipmentLayer.HUMANOID, "default/armors/amethyst_armor_layer_1");
        layers.put(EquipmentLayer.HUMANOID_LEGGINGS, "default/armors/amethyst_armor_layer_2");
        EquipmentSet set = new EquipmentSet("amethyst", layers, List.of("amethyst_helmet"),
                Map.of(EquipmentLayer.HUMANOID, "amethyst_helmet", EquipmentLayer.HUMANOID_LEGGINGS, "amethyst_helmet"));

        EquipmentPackFiles.generateComponentFiles(output, Map.of("amethyst", set), SemanticServerVersion.parse("1.21.4"), Set.of(), Set.of(),
                new ArrayList<>()::add, new ArrayList<>()::add, new ArrayList<>()::add);

        VirtualFile equipmentJson = file(output, "assets/oraxen/equipment/amethyst.json");
        assertNotNull(equipmentJson);
        String json = new String(equipmentJson.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"humanoid\":[{\"texture\":\"oraxen:amethyst\"}]"));
        assertTrue(json.contains("\"humanoid_leggings\":[{\"texture\":\"oraxen:amethyst\"}]"));
        assertArrayEquals(first, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid/amethyst.png").getInputStream().readAllBytes());
        assertArrayEquals(second, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid_leggings/amethyst.png").getInputStream().readAllBytes());
        assertNotNull(file(output, "assets/minecraft/textures/default/armors/amethyst_armor_layer_1.png"));
        assertArrayEquals(first, file(output,
                "assets/minecraft/textures/default/armors/amethyst_armor_layer_1.png").getInputStream().readAllBytes());
    }

    @Test
    void leavesExistingEquipmentJsonAndSkipsCopies() throws Exception {
        List<VirtualFile> output = new ArrayList<>();
        output.add(new VirtualFile("assets/oraxen/equipment", "amethyst.json",
                new ByteArrayInputStream("{\"layers\":{}}".getBytes(StandardCharsets.UTF_8))));
        output.add(new VirtualFile("assets/minecraft/textures/default/armors", "amethyst_armor_layer_1.png",
                new ByteArrayInputStream("texture".getBytes(StandardCharsets.UTF_8))));
        Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        layers.put(EquipmentLayer.HUMANOID, "default/armors/amethyst_armor_layer_1");
        EquipmentSet set = new EquipmentSet("amethyst", layers, List.of("amethyst_helmet"),
                Map.of(EquipmentLayer.HUMANOID, "amethyst_helmet"));
        List<String> debug = new ArrayList<>();

        EquipmentPackFiles.generateComponentFiles(output, Map.of("amethyst", set), SemanticServerVersion.parse("1.21.4"), Set.of(), Set.of(),
                new ArrayList<>()::add, new ArrayList<>()::add, debug::add);

        VirtualFile equipmentJson = file(output, "assets/oraxen/equipment/amethyst.json");
        assertNotNull(equipmentJson);
        assertEquals("{\"layers\":{}}", new String(equipmentJson.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertNull(file(output, "assets/oraxen/textures/entity/equipment/humanoid/amethyst.png"));
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
    void partialMigrationKeepsLegacyLeggings() throws Exception {
        List<VirtualFile> output = partialMigrationFiles();
        output.add(virtualFile("assets/oraxen/textures/entity/equipment/humanoid/amethyst.png.mcmeta",
                new byte[] { 9 }));
        EquipmentSet set = explicitHumanoidSet();

        new ComponentArmorModels().generatePackFiles(output, Map.of("amethyst", set), new HashSet<>(), new HashSet<>(),
                SemanticServerVersion.parse("1.21.4"));

        assertArrayEquals(new byte[] { 3 }, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid/amethyst.png").getInputStream().readAllBytes());
        assertArrayEquals(new byte[] { 2 }, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid_leggings/amethyst.png").getInputStream().readAllBytes());
        assertArrayEquals(new byte[] { 4 }, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid/amethyst.png.mcmeta").getInputStream().readAllBytes());
        VirtualFile equipmentJson = file(output, "assets/oraxen/equipment/amethyst.json");
        assertNotNull(equipmentJson);
        String json = new String(equipmentJson.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"humanoid\""));
        assertTrue(json.contains("\"humanoid_leggings\""));

        List<VirtualFile> trimOutput = partialMigrationFiles();
        EquipmentPackFiles.copyLegacyTrimTextures(trimOutput);
        EquipmentPackFiles.copyTrimTextures(trimOutput, Map.of("amethyst", set), SemanticServerVersion.parse("1.21.4"),
                new ArrayList<>()::add, new ArrayList<>()::add);

        assertArrayEquals(new byte[] { 3 }, file(trimOutput,
                "assets/oraxen/textures/trims/models/armor/amethyst.png").getInputStream().readAllBytes());
        assertArrayEquals(new byte[] { 2 }, file(trimOutput,
                "assets/oraxen/textures/trims/models/armor/amethyst_leggings.png").getInputStream().readAllBytes());
    }

    @Test
    void legacyTextureCopiesKeepOverlayPaths() throws Exception {
        List<VirtualFile> output = new ArrayList<>();
        output.add(virtualFile("modern/assets/minecraft/textures/default/armors/amethyst_armor_layer_1.png",
                new byte[] { 1 }));
        output.add(new VirtualFile("modern/assets/minecraft/textures/default/armors", "unrelated.png", () -> {
            throw new AssertionError("unrelated stream opened");
        }));

        EquipmentPackFiles.copyLegacyComponentTextures(output);
        EquipmentPackFiles.copyLegacyTrimTextures(output);

        assertArrayEquals(new byte[] { 1 }, file(output,
                "modern/assets/oraxen/textures/entity/equipment/humanoid/amethyst.png").getInputStream().readAllBytes());
        assertArrayEquals(new byte[] { 1 }, file(output,
                "modern/assets/oraxen/textures/trims/models/armor/amethyst.png").getInputStream().readAllBytes());
        assertNull(file(output, "assets/oraxen/textures/entity/equipment/humanoid/amethyst.png"));
        assertNull(file(output, "assets/oraxen/textures/trims/models/armor/amethyst.png"));
    }

    @Test
    void explicitCopiesUseFrozenSourcesAcrossEquipmentSets() throws Exception {
        byte[] originalCanonical = new byte[] { 1 };
        byte[] configured = new byte[] { 2 };
        List<VirtualFile> output = new ArrayList<>();
        output.add(virtualFile("assets/minecraft/textures/item/amethyst.png", configured));
        output.add(virtualFile("assets/oraxen/textures/entity/equipment/humanoid/amethyst.png", originalCanonical));
        Map<String, EquipmentSet> sets = new LinkedHashMap<>();
        sets.put("amethyst", humanoidSet("amethyst", "minecraft:item/amethyst"));
        sets.put("ruby", humanoidSet("ruby", "oraxen:entity/equipment/humanoid/amethyst"));

        EquipmentPackFiles.generateComponentFiles(output, sets, SemanticServerVersion.parse("1.21.4"), Set.of(), Set.of(),
                new ArrayList<>()::add, new ArrayList<>()::add, new ArrayList<>()::add);

        assertArrayEquals(configured, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid/amethyst.png").getInputStream().readAllBytes());
        assertArrayEquals(originalCanonical, file(output,
                "assets/oraxen/textures/entity/equipment/humanoid/ruby.png").getInputStream().readAllBytes());
    }

    @Test
    void legacyDetectionIgnoresGeneratedEquipmentCopies() {
        List<VirtualFile> output = new ArrayList<>();
        output.add(new VirtualFile("assets/oraxen/textures/entity/equipment/wings", "magic_elytra.png",
                new ByteArrayInputStream(new byte[0])));

        assertTrue(ComponentArmorModels.elytraPrefixes(output).isEmpty());
    }

    private static List<VirtualFile> partialMigrationFiles() {
        List<VirtualFile> output = new ArrayList<>();
        output.add(virtualFile("assets/minecraft/textures/default/armors/amethyst_armor_layer_1.png", new byte[] { 1 }));
        output.add(virtualFile("assets/minecraft/textures/default/armors/amethyst_armor_layer_2.png", new byte[] { 2 }));
        output.add(virtualFile("assets/minecraft/textures/default/armors/amethyst_top.png", new byte[] { 3 }));
        output.add(virtualFile("assets/minecraft/textures/default/armors/amethyst_top.png.mcmeta", new byte[] { 4 }));
        return output;
    }

    private static EquipmentSet explicitHumanoidSet() {
        return humanoidSet("amethyst", "default/armors/amethyst_top");
    }

    private static EquipmentSet humanoidSet(String id, String texture) {
        Map<EquipmentLayer, String> layers = new EnumMap<>(EquipmentLayer.class);
        layers.put(EquipmentLayer.HUMANOID, texture);
        return new EquipmentSet(id, layers, List.of(id + "_helmet"), Map.of(EquipmentLayer.HUMANOID, id + "_helmet"));
    }

    private static VirtualFile virtualFile(String path, byte[] bytes) {
        int slash = path.lastIndexOf('/');
        return new VirtualFile(path.substring(0, slash), path.substring(slash + 1), new ByteArrayInputStream(bytes));
    }

    private static VirtualFile file(List<VirtualFile> output, String path) {
        return output.stream().filter(file -> file.getPath().equals(path)).findFirst().orElse(null);
    }
}
