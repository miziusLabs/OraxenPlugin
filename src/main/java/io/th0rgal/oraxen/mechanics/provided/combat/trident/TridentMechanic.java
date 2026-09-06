package io.th0rgal.oraxen.mechanics.provided.combat.trident;

import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;

import java.util.Locale;

public class TridentMechanic extends Mechanic {
    private final String model;
    private final String thrownModel;
    private final ItemDisplayTransform transform;
    private final String throwSound;
    private final String hitSound;
    private final String hitGroundSound;
    private final String returnSound;

    public TridentMechanic(MechanicFactory factory, ConfigurationSection section) {
        super(factory, section, item -> {
            item.setType(Material.TRIDENT);
            if (VersionUtil.atOrAbove("1.21.4") && section.isString("appearance.model"))
                item.setItemModel(modelKey(section.getParent().getParent().getName(), false));
            return item;
        });
        model = readModel(section, "appearance.model");
        thrownModel = readModel(section, "appearance.thrown-model");
        transform = readTransform(section);
        throwSound = section.getString("sounds.throw");
        hitSound = section.getString("sounds.hit");
        hitGroundSound = section.getString("sounds.hit-ground", hitSound);
        returnSound = section.getString("sounds.return", throwSound);
    }

    private static ItemDisplayTransform readTransform(ConfigurationSection section) {
        String transform = section.getString("appearance.transform", "NONE");
        try {
            return ItemDisplayTransform.valueOf(transform.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ItemDisplayTransform.NONE;
        }
    }

    private static String readModel(ConfigurationSection section, String path) {
        String model = section.getString(path);
        if (model == null) return null;
        if (model.endsWith(".json")) model = model.substring(0, model.length() - 5);
        NamespacedKey key = NamespacedKey.fromString(model);
        if (key == null || model.isBlank())
            throw new IllegalArgumentException("Invalid trident " + path + ": " + model);
        return key.toString();
    }

    public static NamespacedKey modelKey(String itemId, boolean thrown) {
        return new NamespacedKey("oraxen", "trident/" + itemId + (thrown ? "/thrown" : "/held"));
    }

    public String getModel() { return model; }
    public String getThrownModel() { return thrownModel; }
    public ItemDisplayTransform getTransform() { return transform; }
    public String getThrowSound() { return throwSound; }
    public String getHitSound() { return hitSound; }
    public String getHitGroundSound() { return hitGroundSound; }
    public String getReturnSound() { return returnSound; }
}
