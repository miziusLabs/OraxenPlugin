package io.th0rgal.oraxen.mechanics.provided.misc.invulnerable;

import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class InvulnerableMechanic extends Mechanic {

    private final Set<DamageCause> causes;

    public InvulnerableMechanic(MechanicFactory factory, String itemId, List<?> entries) {
        super(factory, itemId);
        causes = EnumSet.noneOf(DamageCause.class);
        for (Object entry : entries) {
            if (!(entry instanceof String name)) {
                Logs.logWarning("Invalid invulnerable damage cause for " + itemId + ": " + entry);
                continue;
            }
            try {
                causes.add(DamageCause.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                Logs.logWarning("Invalid invulnerable damage cause for " + itemId + ": " + name);
            }
        }
    }

    public boolean protectsFrom(DamageCause cause) {
        return causes.contains(cause);
    }
}
