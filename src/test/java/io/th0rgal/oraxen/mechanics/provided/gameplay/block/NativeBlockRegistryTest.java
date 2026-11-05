package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import com.google.gson.JsonObject;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NativeBlockRegistryTest {
    @Test
    void cachedIdentitiesKeepTheirAppearanceWhenConfigurationOrderOrLegacyValuesChange() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        assertEquals(18, NativeBlockRegistry.allocateVariation(allocations, "first", 18));
        assertEquals(0, NativeBlockRegistry.allocateVariation(allocations, "second", null));

        // Simulate loading persisted identities before parsing a reordered item configuration.
        Map<String, JsonObject> reloaded = new LinkedHashMap<>();
        allocations.forEach((itemId, allocation) -> reloaded.put(itemId, allocation.deepCopy()));
        assertEquals(0, NativeBlockRegistry.allocateVariation(reloaded, "second", 30));
        assertEquals(18, NativeBlockRegistry.allocateVariation(reloaded, "first", null));
        assertEquals(1, NativeBlockRegistry.allocateVariation(reloaded, "added", null));
    }

    @Test
    void collidingAndInvalidLegacyHintsReceiveDistinctValidAppearances() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        assertEquals(17, NativeBlockRegistry.allocateVariation(allocations, "existing", 17));
        assertEquals(0, NativeBlockRegistry.allocateVariation(allocations, "collision", 17));
        assertEquals(1, NativeBlockRegistry.allocateVariation(allocations, "negative", -1));
        assertEquals(2, NativeBlockRegistry.allocateVariation(allocations, "overflow", 775));
    }

    @Test
    void exhaustedAppearancesRejectTheNewItemWithoutChangingPersistedAssignments() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        for (int index = 0; index < 774; index++)
            assertEquals(index, NativeBlockRegistry.allocateVariation(allocations, "item_" + index, null));

        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockRegistry.allocateVariation(allocations, "overflow", null));
        assertEquals(774, allocations.size());
        assertEquals(773, NativeBlockRegistry.allocateVariation(allocations, "item_773", 0));
    }

    @Test
    void corruptCachedVariationsFailBeforeAssigningOrReusingAnIdentity() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        JsonObject invalid = new JsonObject();
        invalid.addProperty("variation", 774);
        allocations.put("invalid", invalid);
        assertThrows(IllegalStateException.class,
                () -> NativeBlockRegistry.allocateVariation(allocations, "invalid", null));
        assertEquals(1, allocations.size());

        invalid.addProperty("variation", 0);
        allocations.put("duplicate", invalid.deepCopy());
        assertThrows(IllegalStateException.class,
                () -> NativeBlockRegistry.allocateVariation(allocations, "added", null));
        assertEquals(2, allocations.size());
    }

    @Test
    void itemIdsThatNormalizeToTheSameNativeKeyCannotOverwriteEachOther() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        NativeBlockRegistry.allocateVariation(allocations, "TestBlock", null);
        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockRegistry.allocateVariation(allocations, "testblock", null));
        assertEquals(1, allocations.size());
    }

    @Test
    void physicalBlockIdsPreserveLegacyWorldKeysAndAssignNewIdentitiesToStableSlots() {
        JsonObject legacy = new JsonObject();
        legacy.addProperty("key", "oraxen:block/old_block");
        assertEquals("oraxen:block/old_block", NativeBlockRegistry.physicalBlockId(legacy, 17));

        JsonObject migrated = new JsonObject();
        migrated.addProperty("key", "oraxen:block/old_block");
        migrated.addProperty("blockId", "oraxen:native_block/slot_17");
        assertEquals("oraxen:native_block/slot_17", NativeBlockRegistry.physicalBlockId(migrated, 17));

        assertEquals("oraxen:native_block/slot_17",
                NativeBlockRegistry.physicalBlockId(new JsonObject(), 17));
        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockRegistry.slotBlockId(NativeBlockRegistry.SLOT_COUNT));
    }

    @Test
    void ownerLookupIncludesNewRotationsBeforeTheyHavePersistedKeys() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        JsonObject base = new JsonObject();
        base.addProperty("itemId", "log");
        base.addProperty("variation", 0);
        base.addProperty("owner", "log");
        base.addProperty("key", "oraxen:block/log");
        allocations.put("log", base);

        JsonObject newRotation = new JsonObject();
        newRotation.addProperty("itemId", "log#rotation=east");
        newRotation.addProperty("variation", 1);
        allocations.put("log#rotation=east", newRotation);

        Map<String, JsonObject> owned = NativeBlockRegistry.identitiesForOwner(allocations, "log");
        assertEquals(2, owned.size());
        assertTrue(owned.containsKey("log#rotation=east"));
        assertFalse(owned.get("log#rotation=east").has("key"));
        assertEquals(1, owned.get("log#rotation=east").get("variation").getAsInt());
    }

    @Test
    void ownerLookupTreatsRotationDelimiterInsideRootIdAsPartOfTheItemId() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        JsonObject root = new JsonObject();
        root.addProperty("itemId", "oak#rotation=east");
        root.addProperty("variation", 0);
        allocations.put("oak#rotation=east", root);

        Map<String, JsonObject> owned = NativeBlockRegistry.identitiesForOwner(allocations, "oak#rotation=east");

        assertEquals(1, owned.size());
        assertTrue(owned.containsKey("oak#rotation=east"));
    }

    @Test
    void ownerLookupKeepsRotatedIdentitiesWhenTheItemIdContainsRotationDelimiter() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        String ownerId = "oak#rotation=east";
        JsonObject root = new JsonObject();
        root.addProperty("itemId", ownerId);
        root.addProperty("variation", 0);
        allocations.put(ownerId, root);

        String rotatedId = ownerId + "#rotation=east";
        JsonObject rotated = new JsonObject();
        rotated.addProperty("itemId", rotatedId);
        rotated.addProperty("variation", 1);
        allocations.put(rotatedId, rotated);

        Map<String, JsonObject> owned = NativeBlockRegistry.identitiesForOwner(allocations, ownerId);

        assertEquals(2, owned.size());
        assertTrue(owned.containsKey(ownerId));
        assertTrue(owned.containsKey(rotatedId));
    }

    @Test
    void rotationReservationDoesNotPartiallyConsumeSlotsWhenThePoolIsTooSmall() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        for (int index = 0; index < NativeBlockRegistry.SLOT_COUNT - 2; index++)
            NativeBlockRegistry.allocateVariation(allocations, "existing_" + index, null);

        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put("new_log", null);
        requested.put("new_log#rotation=east", null);
        requested.put("new_log#rotation=south", null);
        requested.put("new_log#rotation=west", null);

        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockRegistry.reserveVariations(allocations, requested));
        assertEquals(NativeBlockRegistry.SLOT_COUNT - 2, allocations.size());
        assertFalse(allocations.containsKey("new_log"));
        assertFalse(allocations.containsKey("new_log#rotation=east"));
        assertFalse(allocations.containsKey("new_log#rotation=south"));
        assertFalse(allocations.containsKey("new_log#rotation=west"));
    }

    @Test
    void successfulReservationsRemainUncommittedUntilTheirFullRegistrationSetIsReady() {
        Map<String, JsonObject> allocations = new LinkedHashMap<>();
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put("new_log", null);
        requested.put("new_log#rotation=east", null);

        Map<String, Integer> reservation = NativeBlockRegistry.reserveVariations(allocations, requested);
        assertEquals(0, allocations.size());
        assertEquals(0, reservation.get("new_log"));
        assertEquals(1, reservation.get("new_log#rotation=east"));
    }

    @Test
    void registrationBatchStagesMappingsWithoutChangingPublishedAllocations() {
        Map<String, JsonObject> published = new LinkedHashMap<>();
        Map<String, Integer> requested = new LinkedHashMap<>();
        requested.put("stone", null);
        requested.put("stone#rotation=east", null);
        Map<String, Integer> reservations = NativeBlockRegistry.reserveVariations(published, requested);
        BlockData appearance = mock(BlockData.class);
        when(appearance.getAsString()).thenReturn("minecraft:note_block[instrument=harp,note=0,powered=false]");
        List<NativeBlockRegistry.Registration> registrations = List.of(
                new NativeBlockRegistry.Registration("stone", "stone", appearance, 6),
                new NativeBlockRegistry.Registration("stone#rotation=east", "stone", appearance, 6));

        Map<String, JsonObject> candidate = NativeBlockRegistry.stageRegistrations(published, reservations, registrations);

        assertTrue(published.isEmpty());
        assertEquals(2, candidate.size());
        assertEquals("oraxen:block/stone", candidate.get("stone").get("key").getAsString());
        assertEquals(6, candidate.get("stone").get("light").getAsInt());
        assertEquals(1, candidate.get("stone#rotation=east").get("variation").getAsInt());

        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockRegistry.stageRegistrations(published, reservations, registrations.subList(0, 1)));
        assertTrue(published.isEmpty());
    }

    @Test
    void legacyVariationHintsRejectValuesWithoutAComputableNoteState() {
        assertTrue(NativeBlockRegistry.isValidLegacyVariation(0));
        assertTrue(NativeBlockRegistry.isValidLegacyVariation(NativeBlockRegistry.SLOT_COUNT - 1));
        assertFalse(NativeBlockRegistry.isValidLegacyVariation(-1));
        assertFalse(NativeBlockRegistry.isValidLegacyVariation(NativeBlockRegistry.SLOT_COUNT));
        assertFalse(NativeBlockRegistry.isValidLegacyVariation(null));

        assertThrows(IllegalArgumentException.class, () -> NativeBlockStorage.registerLegacy(-1, "invalid_low"));
        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockStorage.registerLegacy(NativeBlockRegistry.SLOT_COUNT, "invalid_high"));
    }

    @Test
    void conflictingLegacyMappingsAreRejectedBeforeRegistration() {
        NativeBlockStorage.resetLegacyMappings();
        try {
            NativeBlockStorage.registerLegacy(5, "first_log");
            assertThrows(IllegalArgumentException.class,
                    () -> NativeBlockStorage.validateLegacy(5, "second_log"));
        } finally {
            NativeBlockStorage.resetLegacyMappings();
        }
    }

    @Test
    void legacyMappingsRemainReadableUntilTheReplacementSetCommits() {
        try {
            NativeBlockStorage.resetLegacyMappings();
            NativeBlockStorage.registerLegacy(5, "old_log");
            NativeBlockStorage.commitLegacyMappings();

            NativeBlockStorage.resetLegacyMappings();
            NativeBlockStorage.registerLegacy(6, "new_log");

            assertEquals(NativeBlockStorage.key("old_log"), NativeBlockStorage.legacyKey(5));
            assertNull(NativeBlockStorage.legacyKey(6));

            NativeBlockStorage.commitLegacyMappings();

            assertNull(NativeBlockStorage.legacyKey(5));
            assertEquals(NativeBlockStorage.key("new_log"), NativeBlockStorage.legacyKey(6));
        } finally {
            NativeBlockStorage.resetLegacyMappings();
            NativeBlockStorage.commitLegacyMappings();
        }
    }

    @Test
    void blockPaletteRepackIsRequiredOnlyWhenRegistryGrowthAddsAnotherBit() {
        assertFalse(NativeBlockRegistry.blockPaletteNeedsRepack(20_000, 30_000));
        assertTrue(NativeBlockRegistry.blockPaletteNeedsRepack(32_768, 32_769));
    }

    @Test
    void omittedLightUsesNativeZeroWhileExplicitLightLevelsStayUnchanged() {
        // LightMechanic reports -1 when an item has no light setting. Native blocks use level 0.
        assertEquals(0, NativeBlockRegistry.normalizeLightLevel(-1));
        assertEquals(0, NativeBlockRegistry.normalizeLightLevel(0));
        assertEquals(12, NativeBlockRegistry.normalizeLightLevel(12));
        assertThrows(IllegalArgumentException.class, () -> NativeBlockRegistry.normalizeLightLevel(16));
    }
}
