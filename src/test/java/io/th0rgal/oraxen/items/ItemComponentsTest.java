package io.th0rgal.oraxen.items;

import io.th0rgal.oraxen.utils.MinecraftVersion;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemComponentsTest {

    @Test
    void mapsLegacySwingAnimationTo26_3Components() {
        assertEquals(List.of("swing_animation"),
                ItemComponents.resolveGenericComponentKeys("swing_animation", new MinecraftVersion("26.2")));
        assertEquals(List.of("attack_animation", "interact_animation"),
                ItemComponents.resolveGenericComponentKeys("swing_animation", new MinecraftVersion("26.3")));
        assertEquals(List.of("attack_animation", "interact_animation"),
                ItemComponents.resolveGenericComponentKeys("SWING_ANIMATION", new MinecraftVersion("1.26.3")));
    }

    @Test
    void keeps26_3ComponentNames() {
        MinecraftVersion version = new MinecraftVersion("26.3");
        assertEquals(List.of("compostable"), ItemComponents.resolveGenericComponentKeys("compostable", version));
        assertEquals(List.of("cooking_fuel"), ItemComponents.resolveGenericComponentKeys("cooking_fuel", version));
        assertEquals(List.of("brewing_fuel"), ItemComponents.resolveGenericComponentKeys("brewing_fuel", version));
        assertEquals(List.of("attack_animation"), ItemComponents.resolveGenericComponentKeys("attack_animation", version));
        assertEquals(List.of("interact_animation"), ItemComponents.resolveGenericComponentKeys("interact_animation", version));
    }

    @Test
    void removesMapColorOnlyOn26_3AndNormalizesNamespaces() {
        assertEquals(List.of(), ItemComponents.resolveGenericComponentKeys("minecraft:map_color", new MinecraftVersion("26.3")));
        assertEquals(List.of("map_color"), ItemComponents.resolveGenericComponentKeys("MAP_COLOR", new MinecraftVersion("26.2")));
        assertEquals(List.of("oraxen:example"), ItemComponents.resolveGenericComponentKeys("ORAXEN:EXAMPLE", new MinecraftVersion("26.3")));
    }

    @Test
    void unprefixedOraxenSyntaxWinsOverExplicitMinecraftNamespace() {
        MemoryConfiguration components = new MemoryConfiguration();
        components.set("minecraft:max_stack_size", 64);
        components.set("max_stack_size", 16);
        components.set("ORAXEN:CUSTOM_THING", true);

        Map<String, Object> normalized = ItemComponents.normalizeComponentKeys(components);
        assertEquals(16, normalized.get("max_stack_size"));
        assertEquals(true, normalized.get("oraxen:custom_thing"));
    }

    @Test
    void legacySwingKeepsNoneAndExplicitModernEntriesOverrideOnlyTheirSide() {
        assertEquals("none", ItemComponents.normalizeSwingAnimationValue("NONE"));

        Map<String, Object> configured = new LinkedHashMap<>();
        configured.put("swing_animation", Map.of("type", "NONE", "duration", 4));
        configured.put("attack_animation", Map.of("type", "whack"));
        assertEquals(List.of("interact_animation"), ItemComponents.resolveLegacySwingComponentKeys(configured));

        Object migrated = ItemComponents.normalizeSwingAnimationValue(configured.get("swing_animation"));
        assertTrue(migrated instanceof Map<?, ?>);
        assertEquals("none", ((Map<?, ?>) migrated).get("type"));
        assertEquals(4, ((Map<?, ?>) migrated).get("duration"));
    }

    @Test
    void equippableNormalizationPrefersAssetIdAndUsesRuntimeFieldNames() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("slot", "MAIN_HAND");
        input.put("model", "oraxen:fallback");
        input.put("asset_id", "oraxen:preferred");
        input.put("allowed_entity_types", List.of("ZOMBIE"));

        Map<String, Object> legacy = ItemComponents.normalizeEquippableMap(input, new MinecraftVersion("1.21.3"));
        assertEquals("mainhand", legacy.get("slot"));
        assertEquals("oraxen:preferred", legacy.get("model"));
        assertFalse(legacy.containsKey("asset_id"));
        assertEquals(List.of("minecraft:zombie"), legacy.get("allowed_entities"));

        Map<String, Object> modern = ItemComponents.normalizeEquippableMap(input, new MinecraftVersion("1.21.4"));
        assertEquals("oraxen:preferred", modern.get("asset_id"));
        assertFalse(modern.containsKey("model"));
    }

    @Test
    void hybridToolRulesConvertLegacySelectorsWithoutDroppingOptions() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("materials", List.of("STONE", "minecraft:DEEPSLATE"));
        rule.put("tag", "minecraft:mineable/pickaxe");
        rule.put("speed", 2.0);
        rule.put("correct_for_drops", true);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("rules", List.of(rule));
        tool.put("can_destroy_blocks_in_creative", true);

        Object convertedValue = ItemComponents.normalizeToolComponentValue(tool, new MinecraftVersion("1.21.5"));
        assertTrue(convertedValue instanceof Map<?, ?>);
        List<?> rules = (List<?>) ((Map<?, ?>) convertedValue).get("rules");
        assertEquals(2, rules.size());
        assertEquals(List.of("minecraft:stone", "minecraft:deepslate"), ((Map<?, ?>) rules.get(0)).get("blocks"));
        assertEquals("#minecraft:mineable/pickaxe", ((Map<?, ?>) rules.get(1)).get("blocks"));
        assertEquals(true, ((Map<?, ?>) rules.get(1)).get("correct_for_drops"));

        Object stripped = ItemComponents.normalizeToolComponentValue(tool, new MinecraftVersion("1.21.4"));
        assertFalse(((Map<?, ?>) stripped).containsKey("can_destroy_blocks_in_creative"));
    }

    @Test
    void detectsNativeRemoveEffectsShapes() {
        assertTrue(ItemComponents.isNativeEffectsShape(
                Map.of("death_effects", List.of(Map.of("type", "remove_effects", "effects", "minecraft:slowness"))),
                "death_effects"));
        assertTrue(ItemComponents.isNativeEffectsShape(
                Map.of("death_effects", List.of(Map.of("type", "remove_effects", "effects", List.of("#minecraft:raid_members")))),
                "death_effects"));
        assertFalse(ItemComponents.isNativeEffectsShape(
                Map.of("death_effects", List.of(Map.of("type", "remove_effects", "effects", List.of("minecraft:slowness")))),
                "death_effects"));
    }

    @Test
    void migratesOldTooltipShapesTogetherWithoutChangingInput() {
        Map<String, Object> modifiers = Map.of("modifiers", List.of(Map.of("id", "minecraft:test")),
                "show_in_tooltip", false);
        Map<String, Object> enchantments = Map.of("levels", Map.of("minecraft:sharpness", 2),
                "show_in_tooltip", false);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("hide_tooltip", true);
        values.put("jukebox_playable", Map.of("song", "minecraft:13", "show_in_tooltip", false));
        values.put("attribute_modifiers", modifiers);
        values.put("enchantments", enchantments);
        values.put("stored_enchantments", Map.of("levels", Map.of("minecraft:mending", 1), "show_in_tooltip", false));
        values.put("dyed_color", Map.of("rgb", 1234, "show_in_tooltip", false));
        ItemComponents.prepareRuntimeValues(values, new LinkedHashSet<>(), new MinecraftVersion("1.21.5"));
        assertEquals("minecraft:13", values.get("jukebox_playable"));
        assertEquals(modifiers.get("modifiers"), values.get("attribute_modifiers"));
        assertEquals(enchantments.get("levels"), values.get("enchantments"));
        assertEquals(Map.of("minecraft:mending", 1), values.get("stored_enchantments"));
        assertEquals(1234, values.get("dyed_color"));
        assertEquals(Map.of("hide_tooltip", true, "hidden_components", List.of(
                "minecraft:jukebox_playable", "minecraft:attribute_modifiers",
                "minecraft:enchantments", "minecraft:stored_enchantments", "minecraft:dyed_color")),
                values.get("tooltip_display"));
        assertTrue(modifiers.containsKey("show_in_tooltip"));
    }

    @Test
    void explicitTooltipFieldsOverrideLegacyHideFlags() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("hide_tooltip", true);
        values.put("tooltip_display", Map.of("hide_tooltip", false, "hidden_components", List.of("minecraft:food")));
        values.put("enchantments", Map.of("levels", Map.of("minecraft:sharpness", 2), "show_in_tooltip", false));
        ItemComponents.prepareRuntimeValues(values, new LinkedHashSet<>(), new MinecraftVersion("26.3"));
        assertEquals(Map.of("hide_tooltip", false, "hidden_components", List.of("minecraft:food")),
                values.get("tooltip_display"));
    }

    @Test
    void distinguishesNamespacedLegacyNativeAndMixedEffects() {
        Map<String, Object> legacy = Map.of("type", "minecraft:apply_effects",
                "effects", Map.of("speed", Map.of("duration", 2)));
        Map<String, Object> nativeEffect = Map.of("type", "minecraft:apply_effects",
                "effects", List.of(Map.of("id", "minecraft:speed", "duration", 40)));
        assertEquals(ItemComponents.EffectsShape.LEGACY,
                ItemComponents.effectsShape(Map.of("on_consume_effects", List.of(legacy)), "on_consume_effects"));
        assertEquals(ItemComponents.EffectsShape.NATIVE,
                ItemComponents.effectsShape(Map.of("on_consume_effects", List.of(nativeEffect)), "on_consume_effects"));
        assertEquals(ItemComponents.EffectsShape.MIXED,
                ItemComponents.effectsShape(Map.of("on_consume_effects", List.of(legacy, nativeEffect)), "on_consume_effects"));
        assertEquals(ItemComponents.EffectsShape.NATIVE,
                ItemComponents.effectsShape(Map.of("sound", Map.of("sound_id", "minecraft:entity.generic.eat")),
                        "on_consume_effects"));
        assertEquals(ItemComponents.EffectsShape.LEGACY, ItemComponents.effectsShape(null, "death_effects"));
    }
}
