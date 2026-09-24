package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.configs.MiningConfigMigration;
import io.th0rgal.oraxen.mechanics.provided.farming.mining.MiningMechanic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningMechanicTest extends MechanicTestSupport {

    @Test
    void parsesWorldRelativeOffsetsAndDeduplicates() {
        MiningMechanic mechanic = new MiningMechanic(mechanicFactory(), "hammer",
                List.of("0,1,0", " -1, 0, 2 ", "0,1,0"));

        assertEquals("hammer", mechanic.getItemID());
        assertEquals(List.of(new MiningMechanic.Offset(0, 1, 0), new MiningMechanic.Offset(-1, 0, 2)), mechanic.getOffsets());
        assertThrows(IllegalArgumentException.class,
                () -> new MiningMechanic(mechanicFactory(), "hammer", List.of("1,2")));
    }

    @Test
    void migratesLegacyRadiusAndDepthWithoutOverwritingMining() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString("hammer:\n  mechanics:\n    bigmining:\n      radius: 1\n      depth: 2\n");
        ConfigurationSection item = configuration.getConfigurationSection("hammer");

        assertTrue(MiningConfigMigration.migrateItem(item));
        assertFalse(item.getConfigurationSection("mechanics").contains("bigmining"));
        List<String> offsets = item.getStringList("mechanics.mining");
        assertEquals(18, offsets.size());
        assertTrue(offsets.contains("-1,-1,0"));
        assertTrue(offsets.contains("1,1,1"));
        assertFalse(MiningConfigMigration.migrateItem(item));

        configuration.set("hammer.mechanics.bigmining.radius", 2);
        configuration.set("hammer.mechanics.bigmining.depth", 3);
        assertTrue(MiningConfigMigration.migrateItem(item));
        assertEquals(offsets, item.getStringList("mechanics.mining"));
    }

    @Test
    void movesFactorySettingsAndPreservesNewValues() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("bigmining.enabled", false);
        configuration.set("bigmining.call_events", false);
        configuration.set("mining.call_events", true);

        assertTrue(MiningConfigMigration.migrateFactory(configuration));
        assertFalse(configuration.contains("bigmining"));
        assertFalse(configuration.getBoolean("mining.enabled"));
        assertTrue(configuration.getBoolean("mining.call_events"));
        assertFalse(MiningConfigMigration.migrateFactory(configuration));
    }
}
