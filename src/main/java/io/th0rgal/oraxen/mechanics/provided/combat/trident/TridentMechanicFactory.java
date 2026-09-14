package io.th0rgal.oraxen.mechanics.provided.combat.trident;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.mechanics.ConfigProperty;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.mechanics.MechanicInfo;
import io.th0rgal.oraxen.mechanics.NestedProperty;
import io.th0rgal.oraxen.mechanics.MechanicsManager;
import io.th0rgal.oraxen.mechanics.PropertyType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

@MechanicInfo(category = "combat", description = "Custom trident models and sounds with vanilla projectile behavior")
@ConfigProperty(name = "sounds", type = PropertyType.OBJECT, nested = {
        @NestedProperty(name = "throw", type = PropertyType.STRING, description = "Sound played on throw"),
        @NestedProperty(name = "hit", type = PropertyType.STRING, description = "Sound played on entity impact"),
        @NestedProperty(name = "hit-ground", type = PropertyType.STRING, description = "Block impact sound; defaults to sounds.hit"),
        @NestedProperty(name = "return", type = PropertyType.STRING, description = "Pickup sound; defaults to sounds.throw")
})
@ConfigProperty(name = "appearance", type = PropertyType.OBJECT, nested = {
        @NestedProperty(name = "model", type = PropertyType.STRING, description = "Handheld and charging model"),
        @NestedProperty(name = "thrown-model", type = PropertyType.STRING, description = "Thrown model"),
        @NestedProperty(name = "transform", type = PropertyType.ENUM, defaultValue = "NONE",
                enumValues = {"NONE", "THIRDPERSON_LEFTHAND", "THIRDPERSON_RIGHTHAND", "FIRSTPERSON_LEFTHAND",
                        "FIRSTPERSON_RIGHTHAND", "HEAD", "GUI", "GROUND", "FIXED"})
})
public class TridentMechanicFactory extends MechanicFactory {
    private final TridentMechanicListener listener;

    public TridentMechanicFactory(ConfigurationSection section) {
        super(section);
        listener = new TridentMechanicListener(this);
        MechanicsManager.registerListeners(OraxenPlugin.get(), getMechanicID(), listener);
    }

    @Override
    public TridentMechanic parse(ConfigurationSection section) {
        TridentMechanic mechanic = new TridentMechanic(this, section);
        mechanic.initializeDisplayItem();
        addToImplemented(mechanic);
        return mechanic;
    }

    @Override
    public TridentMechanic getMechanic(String itemId) {
        return (TridentMechanic) super.getMechanic(itemId);
    }

    public void addDisplayItems(Map<Material, Map<String, ItemBuilder>> items) {
        Map<String, ItemBuilder> paperItems = new LinkedHashMap<>(items.getOrDefault(Material.PAPER, Map.of()));
        for (String itemId : getItems()) {
            TridentMechanic mechanic = getMechanic(itemId);
            if (mechanic.getDisplayItem() != null)
                paperItems.put(mechanic.getDisplayItemId(), mechanic.getDisplayItem());
        }
        if (paperItems.isEmpty()) return;
        Map<String, ItemBuilder> sorted = new LinkedHashMap<>();
        paperItems.entrySet().stream().sorted(Comparator.comparingInt(entry -> {
            Integer value = entry.getValue().getOraxenMeta().getCustomModelData();
            return value == null ? 0 : value;
        })).forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        items.put(Material.PAPER, sorted);
    }

    @Override
    public void onUnregister() {
        listener.close();
    }
}
