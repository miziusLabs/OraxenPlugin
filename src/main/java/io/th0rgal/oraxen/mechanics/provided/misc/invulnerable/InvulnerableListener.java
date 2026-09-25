package io.th0rgal.oraxen.mechanics.provided.misc.invulnerable;

import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

public class InvulnerableListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        InvulnerableMechanicFactory factory = InvulnerableMechanicFactory.get();
        if (factory == null) return;
        InvulnerableMechanic mechanic = factory.getMechanic(item.getItemStack());
        if (mechanic != null && mechanic.protectsFrom(event.getCause()))
            event.setCancelled(true);
    }
}
