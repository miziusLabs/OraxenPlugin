package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
