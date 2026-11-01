package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlockBreakingTest {

    @Test
    void matchingToolRuleTakesPrecedenceOverEarlierNestedFallback() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                breaking:
                  - else:
                      hardness: 4
                      drops:
                        - item: fallback_drop
                  - when: minecraft:diamond_pickaxe
                    hardness: 1
                    drops: []
                """);
        ItemStack pickaxe = mock(ItemStack.class);
        when(pickaxe.getType()).thenReturn(Material.DIAMOND_PICKAXE);
        BlockBreaking breaking = new BlockBreaking(config, "tool_block");

        assertEquals(1, breaking.hardness(pickaxe));
        assertTrue(breaking.drop(pickaxe).getLoots().isEmpty());
        assertEquals(4, breaking.hardness(null));
        assertEquals(1, breaking.drop(null).getLoots().size());
    }

    @Test
    void nestedFallbackLoadsHardnessDropsAndDurabilityFromYaml() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                breaking:
                  - else:
                      hardness: 2.5
                      drops:
                        - item: native_validation
                          silk-touch: required
                      durability:
                        add: 1
                        remove: 3
                """);
        BlockBreaking breaking = new BlockBreaking(config, "native_validation");

        assertEquals(2.5, breaking.hardness(null));
        assertEquals(1, breaking.drop(null).getLoots().size());
        assertEquals(new BlockBreaking.DurabilityAction(1, 3), breaking.durabilityAction(null));
    }

    @Test
    void explicitSiblingPropertiesOverrideNestedFallbackProperties() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                breaking:
                  - else:
                      hardness: 2.5
                      drops:
                        - item: nested_drop
                      durability:
                        remove: 3
                    hardness: 4
                    drops: []
                    durability:
                      add: 2
                """);
        BlockBreaking breaking = new BlockBreaking(config, "native_validation");

        assertEquals(4, breaking.hardness(null));
        assertTrue(breaking.drop(null).getLoots().isEmpty());
        assertEquals(new BlockBreaking.DurabilityAction(2, 0), breaking.durabilityAction(null));
    }

    @Test
    void flatFallbackWithNullElseStillLoadsSiblingProperties() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                breaking:
                  - else:
                    hardness: 3
                    drops:
                      - item: flat_drop
                    durability:
                      remove: 2
                """);
        BlockBreaking breaking = new BlockBreaking(config, "flat_block");

        assertEquals(3, breaking.hardness(null));
        assertEquals(1, breaking.drop(null).getLoots().size());
        assertEquals(new BlockBreaking.DurabilityAction(0, 2), breaking.durabilityAction(null));
    }

    @Test
    void absentBreakingConfigDoesNotOverrideVanillaHardness() {
        ConfigurationSection section = mock(ConfigurationSection.class);
        when(section.getList("breaking")).thenReturn(null);

        BlockBreaking breaking = new BlockBreaking(section, "custom_block");

        assertFalse(breaking.hasHardness(null));
        assertNotNull(breaking.drop(null));
    }

    @Test
    void invalidBreakingConfigDoesNotCreateFallbackHardnessRule() {
        ConfigurationSection section = mock(ConfigurationSection.class);
        doReturn(List.of("invalid")).when(section).getList("breaking");

        BlockBreaking breaking = new BlockBreaking(section, "custom_block");

        assertFalse(breaking.hasHardness(null));
        assertNotNull(breaking.drop(null));
    }

    @Test
    void zeroHardnessDoesNotOverrideVanillaHardness() {
        ConfigurationSection section = mock(ConfigurationSection.class);
        doReturn(List.of(Map.of("else", true, "hardness", 0))).when(section).getList("breaking");

        BlockBreaking breaking = new BlockBreaking(section, "custom_block");

        assertFalse(breaking.hasHardness(null));
    }

    @Test
    void fallbackRuleCanConfigureDurabilityAction() {
        ConfigurationSection section = mock(ConfigurationSection.class);
        doReturn(List.of(Map.of("else", true, "durability", Map.of("add", 0, "remove", 2))))
                .when(section).getList("breaking");

        BlockBreaking breaking = new BlockBreaking(section, "custom_block");
        BlockBreaking.DurabilityAction action = breaking.durabilityAction(null);

        assertNotNull(action);
        assertEquals(0, action.add());
        assertEquals(2, action.remove());
    }
}
