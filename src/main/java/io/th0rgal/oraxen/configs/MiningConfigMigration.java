package io.th0rgal.oraxen.configs;

import io.th0rgal.oraxen.utils.OraxenYaml;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public final class MiningConfigMigration {

    private MiningConfigMigration() {
    }

    public static boolean migrateFactory(ConfigurationSection mechanicsConfig) {
        ConfigurationSection legacy = OraxenYaml.getConfigurationSection(mechanicsConfig, "bigmining");
        if (legacy == null) return false;

        ConfigurationSection mining = OraxenYaml.getConfigurationSection(mechanicsConfig, "mining");
        if (mining == null) mining = mechanicsConfig.createSection("mining");
        for (String key : legacy.getKeys(false)) {
            if (!mining.contains(key)) mining.set(key, legacy.get(key));
        }
        mechanicsConfig.set(legacy.getName(), null);
        OraxenYaml.invalidateKeyCache(mechanicsConfig);
        return true;
    }

    public static boolean migrateItem(ConfigurationSection item) {
        ConfigurationSection mechanics = OraxenYaml.getConfigurationSection(item, "mechanics");
        if (mechanics == null) return false;
        ConfigurationSection legacy = OraxenYaml.getConfigurationSection(mechanics, "bigmining");
        if (legacy == null) return false;

        if (!OraxenYaml.contains(mechanics, "mining")) {
            int radius = Math.max(0, legacy.getInt("radius"));
            int depth = Math.max(0, legacy.getInt("depth"));
            List<String> offsets = new ArrayList<>();
            // The new mechanic uses fixed world axes; legacy depth followed the targeted face.
            for (int z = 0; z < depth; z++)
                for (int y = -radius; y <= radius; y++)
                    for (int x = -radius; x <= radius; x++)
                        offsets.add(x + "," + y + "," + z);
            mechanics.set("mining", offsets);
        }

        mechanics.set(legacy.getName(), null);
        OraxenYaml.invalidateKeyCache(mechanics);
        return true;
    }
}
