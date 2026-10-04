package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.mechanics.provided.combat.trident.TridentMechanic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TridentMechanicTest extends MechanicTestSupport {
    private ConfigurationSection section() {
        return mechanicSection("trident", "appearance.model", "tridents/amethyst",
                "appearance.thrown-model", "custom:tridents/amethyst_thrown");
    }

    @Test
    void defaultsSecondarySoundsAndNormalizesModelPaths() {
        ConfigurationSection section = section();
        section.set("sounds.throw", "trident.throw.sound");
        section.set("sounds.hit", "trident.hit.sound");
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), section);
        assertEquals("minecraft:tridents/amethyst", mechanic.getModel());
        assertEquals("custom:tridents/amethyst_thrown", mechanic.getThrownModel());
        assertEquals(ItemDisplay.ItemDisplayTransform.NONE, mechanic.getTransform());
        assertEquals("trident.hit.sound", mechanic.getGroundSound());
        assertEquals("trident.throw.sound", mechanic.getReturnSound());
        assertNull(mechanic.soundFor("item.trident.riptide_1"));
        assertEquals("trident.hit.sound", mechanic.soundFor("item.trident.hit_ground"));
    }

    @Test
    void readsExplicitTransformAndSounds() {
        ConfigurationSection section = section();
        section.set("appearance.transform", "FIXED");
        section.set("sounds.hit-ground", "custom:ground");
        section.set("sounds.return", "custom:return");
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), section);
        assertEquals(ItemDisplay.ItemDisplayTransform.FIXED, mechanic.getTransform());
        assertEquals("custom:ground", mechanic.getGroundSound());
        assertEquals("custom:return", mechanic.getReturnSound());
    }

    @Test
    void generatesOrdinaryGeometryModelsForHandAndFlight() {
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), section());
        assertEquals("minecraft:model", mechanic.modelDefinition(false).getAsJsonObject("model").get("type").getAsString());
        assertEquals("minecraft:tridents/amethyst", mechanic.modelDefinition(false).getAsJsonObject("model").get("model").getAsString());
        assertEquals("custom:tridents/amethyst_thrown", mechanic.modelDefinition(true).getAsJsonObject("model").get("model").getAsString());
        assertEquals("oraxen:test_item_thrown", mechanic.getThrownItemModel().toString());
    }

    @Test
    void rejectsMissingOrInvalidAppearance() {
        ConfigurationSection section = section();
        section.set("appearance.thrown-model", null);
        assertThrows(IllegalArgumentException.class, () -> new TridentMechanic(mechanicFactory(), section));
        section.set("appearance.thrown-model", "Invalid Path");
        assertThrows(IllegalArgumentException.class, () -> new TridentMechanic(mechanicFactory(), section));
    }

    @Test
    void rejectsInvalidSoundsBeforeSendingPackets() {
        ConfigurationSection section = section();
        section.set("sounds.throw", "Invalid sound");
        assertThrows(IllegalArgumentException.class, () -> new TridentMechanic(mechanicFactory(), section));
    }
}
