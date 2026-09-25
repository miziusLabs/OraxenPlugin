package io.th0rgal.oraxen.mechanics.provided.misc.invulnerable;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.mechanics.MechanicInfo;
import io.th0rgal.oraxen.mechanics.MechanicsManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.List;

@MechanicInfo(category = "misc", description = "Protects dropped items from selected damage causes")
public class InvulnerableMechanicFactory extends MechanicFactory {

    private static InvulnerableMechanicFactory instance;

    public InvulnerableMechanicFactory(ConfigurationSection section) {
        super(section);
        instance = this;
        MechanicsManager.registerListeners(OraxenPlugin.get(), getMechanicID(), new InvulnerableListener());
    }

    @Override
    public Mechanic parse(ConfigurationSection section) {
        return null;
    }

    @Override
    public InvulnerableMechanic parse(String itemId, List<?> entries) {
        InvulnerableMechanic mechanic = new InvulnerableMechanic(this, itemId, entries);
        addToImplemented(mechanic);
        return mechanic;
    }

    public static InvulnerableMechanicFactory get() {
        return instance;
    }

    @Override
    public InvulnerableMechanic getMechanic(ItemStack itemStack) {
        return (InvulnerableMechanic) super.getMechanic(itemStack);
    }
}
