package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.mechanics.provided.misc.invulnerable.InvulnerableMechanic;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvulnerableMechanicTest extends MechanicTestSupport {

    @Test
    void acceptsConfiguredDamageCausesWithoutAffectingOthers() {
        InvulnerableMechanic mechanic = new InvulnerableMechanic(mechanicFactory(), "test_item",
                List.of("lava", "fire", "fire_tick", "block_explosion", "entity_explosion",
                        "lightning", "contact"));

        for (DamageCause cause : List.of(DamageCause.LAVA, DamageCause.FIRE, DamageCause.FIRE_TICK,
                DamageCause.BLOCK_EXPLOSION, DamageCause.ENTITY_EXPLOSION, DamageCause.LIGHTNING,
                DamageCause.CONTACT))
            assertTrue(mechanic.protectsFrom(cause));
        assertFalse(mechanic.protectsFrom(DamageCause.VOID));
    }
}
