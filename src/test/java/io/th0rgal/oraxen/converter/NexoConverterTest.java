package io.th0rgal.oraxen.converter;

import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.Isolated
class NexoConverterTest {
    @TempDir Path directory;

    private Path input() { return directory.resolve("Nexo"); }
    private Path output() { return directory.resolve("output"); }
    private void source(String name, String content) throws IOException {
        Path path = input().resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
    private YamlConfiguration result(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(output().resolve(name).toFile());
        return yaml;
    }

    @Test void convertsItemsGlyphsAndReferencesWithoutChangingSource() throws Exception {
        source("items/nested/tools.yml", """
                sword:
                  material: DIAMOND_SWORD
                  itemname: '<gold>Forest sword'
                  Pack:
                    texture: forest:item/sword
                    custom_model_data: 1056
                  Components:
                    max_damage: 500
                    use_remainder:
                      nexo_item: empty_handle
                  Mechanics:
                    durability:
                      value: 500
                """);
        source("glyphs/chat.yml", """
                heart:
                  texture: forest:chat/heart
                  char: 42001
                  font: forest:chat
                  permission: nexo.emoji.heart
                  placeholders: ['<3']
                  tabcomplete: true
                  ascent: 8
                  height: 8
                """);
        source("recipes/shaped.yml", """
                blade:
                  result:
                    nexo_item: sword
                  ingredients:
                    H:
                      nexo_item: empty_handle
                """);
        source("items/loot.yml", """
                table:
                  Mechanics:
                    furniture:
                      drop:
                        loots:
                          - nexo_item: sword
                            amount: 1
                """);
        String original = Files.readString(input().resolve("items/nested/tools.yml"));
        new NexoConverter().convert(input(), output());
        YamlConfiguration item = result("items/nested/tools.yml");
        assertEquals("<gold>Forest sword", item.getString("sword.name"));
        assertEquals(List.of("forest:item/sword"), item.getStringList("sword.pack.textures"));
        assertTrue(item.getBoolean("sword.pack.generate_model"));
        assertEquals(1056, item.getInt("sword.pack.custom_model_data"));
        assertEquals("empty_handle", item.getString("sword.components.use_remainder.oraxen_item"));
        assertEquals(500, item.getInt("sword.mechanics.durability.value"));
        assertFalse(item.contains("sword.Pack"));
        var plugin = org.mockito.Mockito.mock(io.th0rgal.oraxen.OraxenPlugin.class);
        var configs = org.mockito.Mockito.mock(io.th0rgal.oraxen.configs.ConfigsManager.class);
        var settings = new YamlConfiguration();
        settings.set("pack.generation.auto-generated-models-follow-texture-path", false);
        org.mockito.Mockito.when(plugin.getConfigsManager()).thenReturn(configs);
        org.mockito.Mockito.when(configs.getSettings()).thenReturn(settings);
        io.th0rgal.oraxen.configs.Settings.invalidateCache();
        try (var mocked = org.mockito.Mockito.mockStatic(io.th0rgal.oraxen.OraxenPlugin.class)) {
            mocked.when(io.th0rgal.oraxen.OraxenPlugin::get).thenReturn(plugin);
            var meta = new io.th0rgal.oraxen.items.OraxenMeta();
            meta.setPackInfos(item.getConfigurationSection("sword.pack"));
            assertTrue(meta.shouldGenerateModel());
            assertEquals(List.of("forest:item/sword"), meta.getLayers());
        } finally {
            io.th0rgal.oraxen.configs.Settings.invalidateCache();
        }
        YamlConfiguration glyph = result("glyphs/chat.yml");
        assertEquals("forest:chat", glyph.getString("heart.appearance.font"));
        assertEquals("nexo.emoji.heart", glyph.getString("heart.chat.permission"));
        assertEquals(List.of("<3"), glyph.getStringList("heart.chat.placeholders"));
        assertEquals(String.valueOf((char) 42001), glyph.getString("heart.char"));
        assertEquals("sword", result("recipes/shaped.yml").getString("blade.result.oraxen_item"));
        assertEquals("empty_handle", result("recipes/shaped.yml").getString("blade.ingredients.H.oraxen_item"));
        assertEquals("sword", result("items/loot.yml").getMapList("table.mechanics.furniture.drop.loots").getFirst().get("oraxen_item"));
        assertEquals(original, Files.readString(input().resolve("items/nested/tools.yml")));
    }

    @Test void retainsNamespacedAssetsAndMovesExternalPacksWithoutUnpacking() throws Exception {
        source("pack/assets/forest/models/chair.json", "{\"parent\":\"item/generated\"}");
        source("pack/assets/forest/textures/item/chair.png", "texture-bytes");
        source("pack/external_packs/addon.zip", "zip-bytes");
        source("pack/external_packs/folder/assets/forest/sounds/music.ogg", "sound-bytes");
        NexoConverter.Result converted = new NexoConverter().convert(input(), output());
        assertEquals(4, converted.files());
        assertEquals("texture-bytes", Files.readString(output().resolve("pack/assets/forest/textures/item/chair.png")));
        assertEquals("zip-bytes", Files.readString(output().resolve("pack/uploads/addon.zip")));
        assertTrue(Files.exists(output().resolve("pack/uploads/folder/assets/forest/sounds/music.ogg")));
        assertFalse(Files.exists(output().resolve("pack/models")));
        assertTrue(converted.warnings().isEmpty());
    }

    @Test void convertsFurnitureAndReportsUnsupportedMechanics() throws Exception {
        source("items/furniture.yml", """
                chair:
                  Pack:
                    model: forest:item/chair
                  Mechanics:
                    furniture:
                      hitbox:
                        barrier: ['0,0,0', '0,1,0']
                        interaction: '0,0.5,0 1,1.5'
                      seats: '0,0.5,0 90'
                      lights: '0,1,0 12'
                      properties:
                        display_transform: NONE
                        scale:
                          x: 0.5
                      storage:
                        type: STORAGE
                        rows: 5
                      beds: '0,0,0'
                    vending_machine:
                      price: 10
                """);
        var converted = new NexoConverter().convert(input(), output());
        var yaml = result("items/furniture.yml");
        String base = "chair.mechanics.furniture.";
        assertEquals("DISPLAY_ENTITY", yaml.getString(base + "type"));
        assertEquals(List.of("0,0,0", "0,1,0"), yaml.getStringList(base + "barriers"));
        assertEquals(List.of("0,0.5,0 1,1.5"), yaml.getStringList(base + "hitboxes"));
        assertEquals(List.of("0,0.5,0 90"), yaml.getStringList(base + "seats"));
        assertEquals(5, yaml.getInt(base + "storage.rows"));
        assertFalse(yaml.contains(base + "hitbox"));
        assertFalse(yaml.contains(base + "beds"));
        assertEquals(2, converted.warnings().size());
        assertTrue(Files.readString(output().resolve("conversion-report.txt")).contains("vending_machine"));
    }

    @Test void resolvesThrownItemsAcrossFiles() throws Exception {
        source("items/a.yml", """
                spear:
                  material: TRIDENT
                  Pack:
                    model: forest:item/spear
                    throwing_model: forest:item/spear_charging
                  Mechanics:
                    trident:
                      thrown_item: thrown_spear
                      display_transform: HEAD
                      sounds:
                        hit_ground:
                          sound: forest:spear_ground
                          volume: 0.5
                """);
        source("items/z.yml", """
                thrown_spear:
                  Pack:
                    model: forest:item/spear_thrown
                """);
        new NexoConverter().convert(input(), output());
        var yaml = result("items/a.yml");
        assertEquals("forest:item/spear", yaml.getString("spear.mechanics.trident.appearance.model"));
        assertEquals("forest:item/spear_charging", yaml.getString("spear.mechanics.trident.appearance.charging-model"));
        assertEquals("forest:item/spear_thrown", yaml.getString("spear.mechanics.trident.appearance.thrown-model"));
        assertEquals("HEAD", yaml.getString("spear.mechanics.trident.appearance.transform"));
        assertFalse(yaml.contains("spear.mechanics.trident.thrown_item"));
        assertEquals("forest:spear_ground", yaml.getString("spear.mechanics.trident.sounds.hit-ground"));
    }

    @Test void convertsArmorSoundsDiscsAndPaintings() throws Exception {
        source("items/content.yml", """
                helmet:
                  material: CHAINMAIL_HELMET
                  CustomArmor:
                    id: forest
                    layer1: forest:item/armor_layer_1
                    layer2: forest:item/armor_layer_2
                leggings:
                  material: CHAINMAIL_LEGGINGS
                  CustomArmor:
                    id: forest
                disc:
                  material: MUSIC_DISC_13
                  Components:
                    jukebox_playable:
                      song: forest:music.forest
                painting:
                  material: PAINTING
                  Components:
                    painting_variant: forest:landscape
                """);
        source("pack/assets/forest/textures/item/armor_layer_1.png", "layer-one");
        source("pack/assets/forest/textures/item/armor_layer_2.png", "layer-two");
        source("sounds.yml", """
                settings:
                  generate_sounds: true
                sounds:
                  - id: forest:music.forest
                    sound: forest:music/forest.ogg
                    stream: true
                    jukebox_playable:
                      description: Forest song
                      duration: 120s
                      comparator_output: 12
                """);
        source("paintings.yml", """
                paintings:
                  forest:landscape:
                    asset_id: forest:landscape
                    title: Landscape
                    author: Artist
                    width: 2
                    height: 1
                    random_place: true
                """);
        new NexoConverter().convert(input(), output());
        var yaml = result("items/content.yml");
        assertEquals("nexo:forest", yaml.getString("helmet.components.equippable.model"));
        assertEquals("HEAD", yaml.getString("helmet.components.equippable.slot"));
        assertEquals("LEGS", yaml.getString("leggings.components.equippable.slot"));
        assertFalse(yaml.contains("helmet.CustomArmor"));
        var equipment = JsonParser.parseString(Files.readString(output().resolve("pack/assets/nexo/equipment/forest.json"))).getAsJsonObject();
        assertTrue(equipment.getAsJsonObject("layers").has("humanoid_leggings"));
        assertEquals("layer-one", Files.readString(output().resolve("pack/assets/nexo/textures/entity/equipment/humanoid/forest.png")));
        assertEquals("forest:music.forest", yaml.getString("disc.components.jukebox_playable.song_key"));
        assertEquals("forest:landscape", yaml.getString("painting.components.painting_variant"));
        assertTrue(result("sounds.yml").getBoolean("settings.automatically_generate"));
        assertTrue(result("sounds.yml").getMapList("sounds").getFirst().containsKey("jukebox"));
        var sounds = new io.th0rgal.oraxen.sounds.SoundManager(result("sounds.yml"), message -> fail(message));
        assertEquals(1, sounds.getCustomSounds().size());
        var sound = sounds.getCustomSounds().iterator().next();
        assertTrue(sound.isJukeboxSound());
        assertEquals(120, sound.getLengthInSeconds());
        assertEquals("forest:music.forest", sound.getJukeboxSongId());
        assertTrue(result("paintings.yml").getBoolean("paintings.forest:landscape.random_place"));
    }

    @Test void emitsCurrentNamesAndInheritedFurnitureAppearanceAcrossFiles() throws Exception {
        source("items/templates.yml", """
                base:
                  template: true
                  itemname: Old base name
                  Pack:
                    model: forest:chair
                    custom_model_data: 1234
                  Mechanics:
                    furniture:
                      display_entity_properties:
                        display_transform: FIXED
                """);
        source("items/chairs.yml", """
                inherited:
                  template: base
                  customname: Custom name
                explicit:
                  itemname: Old name
                  displayname: Older name
                  customname: Old custom name
                  name: Current name
                  custom-name: Current custom name
                  Pack:
                    model: forest:inventory
                  Mechanics:
                    furniture:
                      appearance:
                        model: forest:placed
                """);
        new NexoConverter().convert(input(), output());
        var templates = result("items/templates.yml");
        assertEquals("Old base name", templates.getString("base.name"));
        assertFalse(templates.contains("base.itemname"));
        assertFalse(templates.contains("base.mechanics.furniture.appearance"));
        var chairs = result("items/chairs.yml");
        assertEquals("Custom name", chairs.getString("inherited.custom-name"));
        assertEquals("forest:chair", chairs.getString("inherited.mechanics.furniture.appearance.model"));
        assertEquals(1234, chairs.getInt("inherited.mechanics.furniture.appearance.custom_model_data"));
        assertEquals("FIXED", templates.getString("base.mechanics.furniture.properties.display_transform"));
        assertEquals("Current name", chairs.getString("explicit.name"));
        assertEquals("Current custom name", chairs.getString("explicit.custom-name"));
        assertFalse(chairs.contains("explicit.itemname"));
        assertFalse(chairs.contains("explicit.displayname"));
        assertFalse(chairs.contains("explicit.customname"));
        assertEquals("forest:placed", chairs.getString("explicit.mechanics.furniture.appearance.model"));
    }

    @Test void convertsGhastsAndReportsUnsupportedOptionsWithoutLosingOtherHitboxes() throws Exception {
        source("items/furniture.yml", """
                chair:
                  Mechanics:
                    furniture:
                      hitbox:
                        ghast:
                          - '0,0.5,0'
                          - '1,0,0 0.5 90 true'
                          - '0,0,0 2'
                          - 'NaN,0,0 0.25'
                        shulker: '0,0,0'
                        barrier: origin
                        interaction: '0,0,0 1,1'
                """);
        var converted = new NexoConverter().convert(input(), output());
        var chair = result("items/furniture.yml").getConfigurationSection("chair.mechanics.furniture");
        assertEquals(List.of("0,0.5,0 0.25", "1,0,0 0.5"), chair.getStringList("ghasts"));
        assertEquals(List.of("0,0,0"), chair.getStringList("barriers"));
        assertEquals(List.of("0,0,0 1,1"), chair.getStringList("hitboxes"));
        assertFalse(chair.contains("hitbox"));
        assertTrue(converted.warnings().stream().anyMatch(w -> w.contains("rotation and visibility")));
        assertEquals(2, converted.warnings().stream().filter(w -> w.contains("invalid offset or scale")).count());
        assertTrue(converted.warnings().stream().anyMatch(w -> w.contains("1.21.6")));
        assertTrue(converted.warnings().stream().anyMatch(w -> w.contains("shulker")));
    }

    @Test void migratesSharedSettingsAndMiningToCurrentPathsWithExplicitValuesWinning() throws Exception {
        source("settings.yml", """
                Plugin:
                  language: english
                Glyphs:
                  unicode_completions: false
                CustomArmor:
                  type: COMPONENT
                Pack:
                  generation:
                    generate: true
                  upload:
                    s3:
                      secret-key: do-not-copy
                pack:
                  generation:
                    generate: false
                  upload:
                    enabled: true
                    s3:
                      secret-key: do-not-copy
                """);
        source("mechanics.yml", """
                bigmining:
                  enabled: true
                """);
        source("items/tool.yml", """
                tool:
                  Mechanics:
                    bigmining:
                      radius: 2
                      depth: 3
                """);
        new NexoConverter().convert(input(), output());
        var settings = result("settings.yml");
        assertEquals("english", settings.getString("general.language"));
        assertFalse(settings.getBoolean("glyphs.unicode-completions", true));
        assertEquals("COMPONENT", settings.getString("custom-armor.type"));
        assertFalse(settings.getBoolean("pack.generation.generate", true));
        assertFalse(settings.contains("Pack"));
        assertFalse(settings.contains("pack.upload"));
        assertTrue(result("mechanics.yml").getBoolean("mining.enabled"));
        var tool = result("items/tool.yml");
        assertEquals(2, tool.getInt("tool.mechanics.mining.radius"));
        assertEquals(3, tool.getInt("tool.mechanics.mining.depth"));
        assertFalse(tool.contains("tool.mechanics.bigmining"));
    }

    @Test void filtersSettingsAndPreservesExplicitTargetValues() throws Exception {
        source("settings.yml", """
                debug: true
                Pack:
                  generation:
                    generate: false
                  server:
                    type: NONE
                NexoOnly:
                  enabled: true
                """);
        source("items/example.yml", """
                item:
                  Pack:
                    model: forest:item/old
                  pack:
                    model: forest:item/new
                  Components:
                    equippable:
                      asset_id: forest:armor
                      model: forest:explicit
                      slot: HEAD
                """);
        var converted = new NexoConverter().convert(input(), output());
        assertFalse(result("settings.yml").getBoolean("pack.generation.generate"));
        assertFalse(result("settings.yml").contains("NexoOnly"));
        assertFalse(result("settings.yml").getBoolean("pack.upload.enabled", true));
        assertFalse(result("settings.yml").getBoolean("pack.dispatch.send", true));
        assertTrue(converted.warnings().stream().anyMatch(w -> w.contains("pack.server.type")));
        assertEquals("forest:item/new", result("items/example.yml").getString("item.pack.model"));
        assertEquals("forest:explicit", result("items/example.yml").getString("item.components.equippable.model"));
    }

    @Test void preservesGlyphDefaultsAndExpandsFurnitureBarrierRanges() throws Exception {
        source("settings.yml", """
                Glyphs:
                  default_font: forest:icons
                  default_permission: 'forest.glyph.<glyphid>'
                  default_shadow_color: '#11000000'
                """);
        source("glyphs/icons.yml", """
                heart:
                  texture: forest:heart
                """);
        source("items/blocks.yml", """
                chair:
                  Mechanics:
                    furniture:
                      hitbox:
                        barrier: ['-1..1,0,0', origin]
                plant:
                  Mechanics:
                    stringblock:
                      custom_variation: 4
                """);
        new NexoConverter().convert(input(), output());
        var glyph = result("glyphs/icons.yml");
        assertEquals("forest:icons", glyph.getString("heart.appearance.font"));
        assertEquals("forest.glyph.heart", glyph.getString("heart.chat.permission"));
        assertEquals("#11000000", glyph.getString("heart.appearance.shadow_color"));
        var block = result("items/blocks.yml");
        assertEquals(4, block.getStringList("chair.mechanics.furniture.barriers").size());
        assertEquals("STRING", block.getString("plant.mechanics.block.type"));
        assertEquals(4, block.getInt("plant.mechanics.block.custom_variation"));
    }

    @Test void convertsGeneratedItemModelIdsButPreservesSuppliedDefinitions() throws Exception {
        source("items/models.yml", """
                generated:
                  Pack:
                    model: forest:item/generated
                  Components:
                    item_model: nexo:generated
                supplied:
                  Pack:
                    model: forest:item/supplied
                  Components:
                    item_model: nexo:supplied
                """);
        String definition = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"forest:item/supplied\"}}";
        source("pack/assets/nexo/items/supplied.json", definition);
        new NexoConverter().convert(input(), output());
        var yaml = result("items/models.yml");
        assertEquals("oraxen:generated", yaml.getString("generated.components.item_model"));
        assertEquals("nexo:supplied", yaml.getString("supplied.components.item_model"));
        assertEquals(definition, Files.readString(output().resolve("pack/assets/nexo/items/supplied.json")));
    }

    @Test void rejectsMalformedYamlWithoutPublishingPartialOutput() throws Exception {
        source("items/a.yml", "item: {}\n");
        source("items/b.yml", "item: [\n");
        assertThrows(IOException.class, () -> new NexoConverter().convert(input(), output()));
        assertFalse(Files.exists(output()));
        try (var children = Files.list(directory)) {
            assertEquals(List.of(input()), children.toList());
        }
    }

    @Test void refusesToOverwriteOutputAndLeavesLiveFilesAlone() throws Exception {
        source("items/a.yml", "item: {}\n");
        Files.createDirectories(output());
        Files.writeString(output().resolve("keep.txt"), "previous conversion");
        Files.writeString(directory.resolve("live.yml"), "live setup");
        assertThrows(IOException.class, () -> new NexoConverter().convert(input(), output()));
        assertEquals("previous conversion", Files.readString(output().resolve("keep.txt")));
        assertEquals("live setup", Files.readString(directory.resolve("live.yml")));
    }

    @Test void rejectsDuplicateItemIdsAndSymlinkedInput() throws Exception {
        source("items/a.yml", "item: {}\n");
        source("items/b.yml", "item: {}\n");
        assertThrows(IOException.class, () -> new NexoConverter().convert(input(), output()));
        Files.delete(input().resolve("items/b.yml"));
        Path outside = Files.writeString(directory.resolve("private.txt"), "not an asset");
        Files.createSymbolicLink(input().resolve("linked.txt"), outside);
        assertThrows(IOException.class, () -> new NexoConverter().convert(input(), output()));
        assertFalse(Files.exists(output()));
    }
}
