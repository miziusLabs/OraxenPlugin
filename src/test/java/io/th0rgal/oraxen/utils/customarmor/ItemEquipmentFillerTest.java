package io.th0rgal.oraxen.utils.customarmor;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.utils.MinecraftVersion;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemEquipmentFillerTest {

    @Test
    void infersSlotsInPriorityOrder() {
        assertEquals("LEGS", ItemEquipmentFiller.inferSlotName("LEGS", "HEAD",
                Set.of(EquipmentLayer.HUMANOID)));
        assertEquals("HEAD", ItemEquipmentFiller.inferSlotName(null, "HEAD",
                Set.of(EquipmentLayer.HUMANOID)));
        assertEquals("BODY", ItemEquipmentFiller.inferSlotName(null, "HAND",
                List.of(EquipmentLayer.HUMANOID, EquipmentLayer.WOLF_BODY)));
        assertNull(ItemEquipmentFiller.inferSlotName(null, "OFF_HAND", Set.of(EquipmentLayer.HUMANOID)));
    }

    @Test
    void infersAllowedEntitiesOnlyForMobSlots() {
        assertEquals(List.of("HORSE", "ZOMBIE_HORSE"), ItemEquipmentFiller.allowedEntityTypeNames("BODY",
                List.of(EquipmentLayer.HORSE_BODY)));
        assertEquals(List.of("PIG"), ItemEquipmentFiller.allowedEntityTypeNames("SADDLE",
                List.of(EquipmentLayer.PIG_SADDLE)));
        assertEquals(List.of(), ItemEquipmentFiller.allowedEntityTypeNames("CHEST",
                List.of(EquipmentLayer.WOLF_BODY)));
    }

    @Test
    void filtersAllowedEntitiesBySelectedSlotForMixedAmethystLayers() {
        List<EquipmentLayer> layers = List.of(EquipmentLayer.HORSE_BODY, EquipmentLayer.PIG_SADDLE);

        assertEquals(List.of("HORSE", "ZOMBIE_HORSE"), ItemEquipmentFiller.allowedEntityTypeNames("BODY", layers));
        assertEquals(List.of("PIG"), ItemEquipmentFiller.allowedEntityTypeNames("SADDLE", layers));
    }

    @Test
    void usesTrimsOnlyForHumanoidLayersOrSetMembers() {
        assertFalse(ItemEquipmentFiller.usesTrimLayers(Set.of()));
        assertTrue(ItemEquipmentFiller.usesTrimLayers(Set.of(EquipmentLayer.HUMANOID)));
        assertTrue(ItemEquipmentFiller.usesTrimLayers(Set.of(EquipmentLayer.HUMANOID_LEGGINGS)));
        assertTrue(ItemEquipmentFiller.usesTrimLayers(Set.of(EquipmentLayer.HUMANOID, EquipmentLayer.WINGS)));
        assertFalse(ItemEquipmentFiller.usesTrimLayers(Set.of(EquipmentLayer.WINGS)));
        assertFalse(ItemEquipmentFiller.usesTrimLayers(Set.of(EquipmentLayer.WOLF_BODY)));
    }

    @Test
    void usesVanillaEquipOnInteractDefaultsForMobEquipment() {
        assertTrue(ItemEquipmentFiller.defaultEquipOnInteract("SADDLE", Set.of(EquipmentLayer.PIG_SADDLE)));
        assertTrue(ItemEquipmentFiller.defaultEquipOnInteract("BODY", Set.of(EquipmentLayer.HAPPY_GHAST_BODY)));
        assertFalse(ItemEquipmentFiller.defaultEquipOnInteract("BODY", Set.of(EquipmentLayer.WOLF_BODY)));
        assertFalse(ItemEquipmentFiller.defaultEquipOnInteract("BODY", Set.of(EquipmentLayer.HORSE_BODY)));
    }


    @Test
    void sharedLayersSupplyMobDefaultsToEveryItemInTheSet() {
        ConfigurationSection pig = load("""
                pig_item:
                  equipment:
                    id: amethyst
                    pig_saddle: default/armors/amethyst_pig
                """, "pig_item");
        ConfigurationSection horse = load("""
                horse_item:
                  equipment:
                    id: amethyst
                    horse_saddle: default/armors/amethyst_horse
                """, "horse_item");
        ConfigurationSection shared = load("""
                shared_item:
                  equipment:
                    id: amethyst
                """, "shared_item");
        Material paper = mock(Material.class);
        when(paper.getEquipmentSlot()).thenReturn(EquipmentSlot.HAND);
        EquippableComponent pigComponent = mock(EquippableComponent.class);
        EquippableComponent horseComponent = mock(EquippableComponent.class);
        EquippableComponent sharedComponent = mock(EquippableComponent.class);
        try (MockedStatic<OraxenPlugin> plugins = mockStatic(OraxenPlugin.class);
                MockedStatic<CustomArmorType> armorTypes = mockStatic(CustomArmorType.class);
                MockedStatic<VersionUtil> versions = mockStatic(VersionUtil.class);
                MockedStatic<MinecraftVersion> minecraftVersions = mockStatic(MinecraftVersion.class);
                MockedStatic<ItemStack> itemStacks = mockStatic(ItemStack.class)) {
            OraxenPlugin plugin = mock(OraxenPlugin.class);
            when(plugin.namespace()).thenReturn("oraxen");
            plugins.when(OraxenPlugin::get).thenReturn(plugin);
            ItemBuilder pigBuilder = equipmentBuilder();
            ItemBuilder horseBuilder = equipmentBuilder();
            ItemBuilder sharedBuilder = equipmentBuilder();
            when(pigBuilder.getEquippableComponent()).thenReturn(pigComponent);
            when(horseBuilder.getEquippableComponent()).thenReturn(horseComponent);
            when(sharedBuilder.getEquippableComponent()).thenReturn(sharedComponent);
            armorTypes.when(CustomArmorType::getSetting).thenReturn(CustomArmorType.COMPONENT);
            versions.when(() -> VersionUtil.atOrAbove(any(String.class))).thenReturn(true);
            minecraftVersions.when(MinecraftVersion::getCurrentVersion).thenReturn(new MinecraftVersion(1, 21, 5));
            itemStacks.when(() -> ItemStack.of(paper)).thenThrow(new NoSuchMethodError());

            ItemEquipmentFiller.prepare(pigBuilder, paper, pig);
            ItemEquipmentFiller.prepare(horseBuilder, paper, horse);
            ItemEquipmentFiller.prepare(sharedBuilder, paper, shared);
            Map<String, ItemBuilder> items = new LinkedHashMap<>();
            items.put("pig_item", pigBuilder);
            items.put("horse_item", horseBuilder);
            items.put("shared_item", sharedBuilder);
            ItemEquipmentFiller.resolveSharedDefaults(items);
        }

        for (EquippableComponent component : List.of(pigComponent, horseComponent, sharedComponent)) {
            verify(component).setSlot(EquipmentSlot.SADDLE);
            verify(component).setAllowedEntities(List.of(EntityType.PIG, EntityType.HORSE));
        }
    }

    private static ItemBuilder equipmentBuilder() {
        ItemBuilder builder = mock(ItemBuilder.class);
        doCallRealMethod().when(builder).setEquipment(any());
        doCallRealMethod().when(builder).hasEquipment();
        doCallRealMethod().when(builder).getEquipment();
        doCallRealMethod().when(builder).deferEquipmentDefaults(any());
        doCallRealMethod().when(builder).resolveEquipmentDefaults(any());
        return builder;
    }

    private static ConfigurationSection load(String yaml, String itemId) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(yaml);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return configuration.getConfigurationSection(itemId);
    }
}
