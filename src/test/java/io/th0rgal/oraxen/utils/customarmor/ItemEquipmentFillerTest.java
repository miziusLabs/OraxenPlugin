package io.th0rgal.oraxen.utils.customarmor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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

}
