package io.th0rgal.oraxen.mechanics.provided.combat.trident;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class TridentMechanicListener implements Listener {
    private final TridentMechanicFactory factory;
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public TridentMechanicListener(TridentMechanicFactory factory) {
        this.factory = factory;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;
        TridentMechanic mechanic = mechanic(trident);
        if (mechanic == null) return;
        play(trident.getLocation(), mechanic.getThrowSound());
        attach(trident, mechanic);
    }

    @EventHandler
    public void onAdd(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;
        TridentMechanic mechanic = mechanic(trident);
        if (mechanic != null) attach(trident, mechanic);
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        Flight flight = flights.remove(event.getEntity().getUniqueId());
        if (flight != null) flight.removed.set(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Trident trident)) return;
        TridentMechanic mechanic = mechanic(trident);
        if (mechanic == null) return;
        // Use the projectile's location: an impact target can belong to another Folia region.
        if (event.getHitEntity() != null) play(trident.getLocation(), mechanic.getHitSound());
        else if (event.getHitBlock() != null) play(trident.getLocation(), mechanic.getHitGroundSound());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(PlayerPickupArrowEvent event) {
        if (!(event.getArrow() instanceof Trident trident)) return;
        TridentMechanic mechanic = mechanic(trident);
        if (mechanic != null) play(event.getPlayer().getLocation(), mechanic.getReturnSound());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        // Tracking callbacks need only immutable identity, never another region's entity state.
        Flight flight = flights.get(event.getEntity().getUniqueId());
        if (flight != null && !flight.removed.get()) event.setCancelled(true);
    }

    private TridentMechanic mechanic(Trident trident) {
        return (TridentMechanic) factory.getMechanic(trident.getItemStack());
    }

    private static void play(Location location, String sound) {
        if (sound != null && !sound.isBlank()) location.getWorld().playSound(location, sound, 1f, 1f);
    }

    private void attach(Trident trident, TridentMechanic mechanic) {
        if (closed || mechanic.getDisplayItem() == null || flights.containsKey(trident.getUniqueId())) return;
        Flight flight = new Flight(trident.getLocation());
        if (flights.putIfAbsent(trident.getUniqueId(), flight) != null) return;
        ItemStack item = mechanic.createDisplayStack();
        // The display is cosmetic; the original stack remains on the projectile for pickup.
        ItemDisplay display;
        try {
            display = trident.getWorld().spawn(flight.location, ItemDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setGravity(false);
                entity.setItemStack(item);
                entity.setItemDisplayTransform(mechanic.getTransform());
                entity.setInterpolationDuration(1);
                if (VersionUtil.atOrAbove("1.20.2")) entity.setTeleportDuration(1);
            });
        } catch (RuntimeException exception) {
            flights.remove(trident.getUniqueId(), flight);
            throw exception;
        }
        var plugin = OraxenPlugin.get();
        var projectileTask = trident.getScheduler().runAtFixedRate(plugin, task -> {
            if (closed || flight.removed.get() || !trident.isValid()) {
                flight.removed.set(true);
                flights.remove(trident.getUniqueId(), flight);
                task.cancel();
                return;
            }
            flight.location = trident.getLocation();
        }, () -> {
            flight.removed.set(true);
            flights.remove(trident.getUniqueId(), flight);
        }, 1, 1);
        if (projectileTask == null) flight.removed.set(true);
        var displayTask = display.getScheduler().runAtFixedRate(plugin, task -> {
            if (closed || flight.removed.get()) {
                display.remove();
                task.cancel();
                return;
            }
            if (!flight.teleporting.compareAndSet(false, true)) return;
            Location target = flight.location.clone();
            // Apply the projectile's pose in the display's own region, then move asynchronously.
            Quaternionf rotation = new Quaternionf().rotationYXZ(
                    (float) Math.toRadians(-target.getYaw()), (float) Math.toRadians(target.getPitch()), 0);
            display.setTransformation(new Transformation(new Vector3f(), rotation, new Vector3f(1), new Quaternionf()));
            target.setYaw(0);
            target.setPitch(0);
            display.teleportAsync(target).whenComplete((success, error) -> {
                flight.teleporting.set(false);
                if (error != null || !Boolean.TRUE.equals(success)) flight.removed.set(true);
            });
        }, () -> flight.removed.set(true), 1, 1);
        if (displayTask == null) flight.removed.set(true);
    }

    public void close() {
        closed = true;
        flights.values().forEach(flight -> flight.removed.set(true));
        flights.clear();
    }

    private static final class Flight {
        private volatile Location location;
        private final AtomicBoolean removed = new AtomicBoolean();
        private final AtomicBoolean teleporting = new AtomicBoolean();

        private Flight(Location location) {
            this.location = location;
        }
    }
}
