package io.th0rgal.oraxen.mechanics.provided.combat.trident;

import com.google.gson.JsonObject;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.api.events.OraxenPackGeneratedEvent;
import io.th0rgal.oraxen.mechanics.*;
import io.th0rgal.oraxen.utils.VirtualFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@MechanicInfo(category = "combat", description = "Custom trident models and sounds with vanilla flight and enchantments")
@ConfigProperty(name = "sounds", type = PropertyType.OBJECT, nested = {
        @NestedProperty(name = "throw", type = PropertyType.STRING, description = "Sound played on launch"),
        @NestedProperty(name = "hit", type = PropertyType.STRING, description = "Sound played on entity impact"),
        @NestedProperty(name = "hit-ground", type = PropertyType.STRING, description = "Block impact sound; defaults to hit"),
        @NestedProperty(name = "return", type = PropertyType.STRING, description = "Pickup sound; defaults to throw")
})
@ConfigProperty(name = "appearance", type = PropertyType.OBJECT, nested = {
        @NestedProperty(name = "model", type = PropertyType.STRING, description = "Handheld and charging model (1.21.4+)"),
        @NestedProperty(name = "thrown-model", type = PropertyType.STRING, description = "Thrown model (1.21.4+)"),
        @NestedProperty(name = "transform", type = PropertyType.ENUM, defaultValue = "NONE",
                enumValues = {"NONE", "THIRDPERSON_LEFTHAND", "THIRDPERSON_RIGHTHAND",
                        "FIRSTPERSON_LEFTHAND", "FIRSTPERSON_RIGHTHAND", "HEAD", "GUI", "GROUND", "FIXED"},
                description = "Display transform used by the thrown model")
})
public class TridentMechanicFactory extends MechanicFactory implements Listener {
    private final TridentMechanicListener listener;

    public TridentMechanicFactory(ConfigurationSection section) {
        super(section);
        listener = new TridentMechanicListener(this);
        MechanicsManager.registerListeners(OraxenPlugin.get(), getMechanicID(), listener, this);
    }

    @Override
    public TridentMechanic parse(ConfigurationSection section) {
        TridentMechanic mechanic = new TridentMechanic(this, section);
        addToImplemented(mechanic);
        return mechanic;
    }

    @Override
    public TridentMechanic getMechanic(ItemStack item) {
        return (TridentMechanic) super.getMechanic(item);
    }

    @Override
    public void onUnregister() {
        listener.close();
    }

    @EventHandler
    public void onPackGeneration(OraxenPackGeneratedEvent event) {
        for (String itemId : getItems()) {
            TridentMechanic mechanic = (TridentMechanic) getMechanic(itemId);
            addDefinition(event.getOutput(), itemId, false, mechanic.getModel());
            addDefinition(event.getOutput(), itemId, true, mechanic.getThrownModel());
        }
    }

    private void addDefinition(List<VirtualFile> output, String itemId, boolean thrown, String model) {
        if (model == null) return;
        String key = TridentMechanic.modelKey(itemId, thrown).getKey();
        String path = "assets/oraxen/items/" + key + ".json";
        output.removeIf(file -> file.getPath().equals(path));
        output.add(new VirtualFile("assets/oraxen/items", key + ".json",
                new ByteArrayInputStream(createModelDefinition(model).toString().getBytes(StandardCharsets.UTF_8))));
    }

    public static JsonObject createModelDefinition(String modelPath) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", modelPath);
        JsonObject root = new JsonObject();
        root.add("model", model);
        return root;
    }
}
