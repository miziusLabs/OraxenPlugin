package io.th0rgal.oraxen.mechanics.provided.misc.invulnerable;

import io.th0rgal.oraxen.utils.VersionUtil;
import net.kyori.adventure.util.TriState;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.ItemSpawnEvent;

public class InvulnerableListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!VersionUtil.atOrAbove("1.21.5")) return;
        Item item = event.getEntity();
        InvulnerableMechanicFactory factory = InvulnerableMechanicFactory.get();
        if (factory == null) return;
        InvulnerableMechanic mechanic = factory.getMechanic(item.getItemStack());
        if (mechanic != null && protectsFromFire(mechanic))
            item.setVisualFire(TriState.FALSE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;
        InvulnerableMechanicFactory factory = InvulnerableMechanicFactory.get();
        if (factory == null) return;
        InvulnerableMechanic mechanic = factory.getMechanic(item.getItemStack());
        if (mechanic != null && mechanic.protectsFrom(event.getCause())) {
            event.setCancelled(true);
            if (protectsFromFire(mechanic) && VersionUtil.atOrAbove("1.21.5"))
                item.setVisualFire(TriState.FALSE);
        }
    }

    private boolean protectsFromFire(InvulnerableMechanic mechanic) {
        return mechanic.protectsFrom(DamageCause.LAVA)
                || mechanic.protectsFrom(DamageCause.FIRE)
                || mechanic.protectsFrom(DamageCause.FIRE_TICK);
    }
}
