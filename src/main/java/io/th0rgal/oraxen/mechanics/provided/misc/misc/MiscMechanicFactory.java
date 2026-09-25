package io.th0rgal.oraxen.mechanics.provided.misc.misc;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.mechanics.ConfigProperty;
import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.mechanics.MechanicInfo;
import io.th0rgal.oraxen.mechanics.MechanicsManager;
import io.th0rgal.oraxen.mechanics.PropertyType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

@MechanicInfo(
        category = "misc",
        description = "Miscellaneous item properties"
)
public class MiscMechanicFactory extends MechanicFactory {

    @ConfigProperty(type = PropertyType.BOOLEAN, description = "Whether item can break music discs", defaultValue = "false")
    public static final String PROP_BREAK_MUSIC_DISCS = "break_music_discs";

    @ConfigProperty(type = PropertyType.BOOLEAN, description = "Whether item renaming in anvils is prevented", defaultValue = "false")
    public static final String PROP_PREVENT_RENAMING = "prevent_renaming";

    private static MiscMechanicFactory instance;

    public MiscMechanicFactory(ConfigurationSection section) {
        super(section);
        MechanicsManager.registerListeners(OraxenPlugin.get(), getMechanicID(), new MiscListener(this));
        instance = this;
    }

    @Override
    public Mechanic parse(ConfigurationSection section) {
        MiscMechanic mechanic = new MiscMechanic(this, section);

        addToImplemented(mechanic);
        return mechanic;
    }

    public static MiscMechanicFactory get() {
        return instance;
    }

    @Override
    public MiscMechanic getMechanic(String itemID) {
        return (MiscMechanic) super.getMechanic(itemID);
    }

    @Override
    public MiscMechanic getMechanic(ItemStack itemStack) {
        return (MiscMechanic) super.getMechanic(itemStack);
    }
}
