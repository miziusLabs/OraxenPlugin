package io.th0rgal.oraxen.mechanics.provided.combat.trident;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
    private boolean stopped;

    public TridentMechanicListener(TridentMechanicFactory factory) {
        this.factory = factory;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Trident trident)) return;
        TridentMechanic mechanic = factory.getMechanic(trident.getItemStack());
        if (mechanic != null) play(trident.getLocation(), mechanic.getThrowSound());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdd(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof Trident trident) || !VersionUtil.atOrAbove("1.21.4")) return;
        TridentMechanic mechanic = factory.getMechanic(trident.getItemStack());
        if (mechanic == null || mechanic.getThrownModel() == null) return;
        // Delay until the spawn has completed. The entity scheduler follows the projectile across regions.
        SchedulerUtil.runForEntity(trident, () -> {
            synchronized (this) {
                if (stopped || !trident.isValid() || flights.containsKey(trident.getUniqueId())) return;
                Flight flight = new Flight(trident, mechanic);
                flights.put(trident.getUniqueId(), flight);
                flight.start();
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent event) {
        if (event.isCancelled() || !(event.getEntity() instanceof Trident trident)) return;
        TridentMechanic mechanic = factory.getMechanic(trident.getItemStack());
        if (mechanic == null) return;
        if (event.getHitEntity() != null) play(trident.getLocation(), mechanic.getHitSound());
        else if (event.getHitBlock() != null) play(trident.getLocation(), mechanic.getHitGroundSound());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(PlayerPickupArrowEvent event) {
        if (event.isCancelled() || !(event.getArrow() instanceof Trident trident)) return;
        TridentMechanic mechanic = factory.getMechanic(trident.getItemStack());
        if (mechanic != null) play(event.getPlayer().getLocation(), mechanic.getReturnSound());
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        Flight flight = flights.remove(event.getEntity().getUniqueId());
        if (flight != null) flight.close();
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin() == OraxenPlugin.get()) close();
    }

    public synchronized void close() {
        stopped = true;
        flights.values().forEach(Flight::close);
        flights.clear();
    }

    private static void play(Location location, String sound) {
        if (sound != null && !sound.isBlank()) location.getWorld().playSound(location, sound, 1.0f, 1.0f);
    }

    private final class Flight {
        private final Trident trident;
        private final ItemDisplay display;
        private final boolean visible;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean teleporting = new AtomicBoolean();
        private volatile Location position;
        private volatile SchedulerUtil.ScheduledTask flightTask;
        private volatile SchedulerUtil.ScheduledTask displayTask;

        private Flight(Trident trident, TridentMechanic mechanic) {
            this.trident = trident;
            visible = trident.isVisibleByDefault();
            position = trident.getLocation();
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setItemModel(TridentMechanic.modelKey(mechanic.getItemID(), true));
            item.setItemMeta(meta);
            display = position.getWorld().spawn(position, ItemDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setItemDisplayTransform(mechanic.getTransform());
                entity.setItemStack(item);
                entity.setTeleportDuration(1);
                entity.setTransformation(transformation(position));
                entity.setRotation(0, 0);
            });
        }

        private void start() {
            if (!display.isValid()) {
                close();
                return;
            }
            trident.setVisibleByDefault(false);
            flightTask = SchedulerUtil.runForEntityTimer(OraxenPlugin.get(), trident, 1, 1,
                    () -> position = trident.getLocation(), this::retire);
            displayTask = SchedulerUtil.runForEntityTimer(OraxenPlugin.get(), display, 1, 1, () -> {
                if (closed.get() || !teleporting.compareAndSet(false, true)) return;
                // Only immutable location snapshots cross region boundaries. Each entity is changed on its own scheduler.
                Location target = position.clone();
                display.setTransformation(transformation(target));
                target.setYaw(0);
                target.setPitch(0);
                try {
                    display.teleportAsync(target).whenComplete((success, error) -> {
                        teleporting.set(false);
                        if (error != null || !Boolean.TRUE.equals(success)) retire();
                    });
                } catch (RuntimeException exception) {
                    teleporting.set(false);
                    close();
                }
            }, this::retire);
            if (flightTask == null || displayTask == null) close();
            if (closed.get()) {
                if (flightTask != null) flightTask.cancel();
                if (displayTask != null) displayTask.cancel();
            }
        }

        private Transformation transformation(Location location) {
            return new Transformation(new Vector3f(), new Quaternionf().rotationYXZ(
                    (float) Math.toRadians(-location.getYaw()),
                    (float) Math.toRadians(location.getPitch()), 0), new Vector3f(1), new Quaternionf());
        }

        private void close() {
            close(false);
        }

        private void retire() {
            // Retired callbacks may schedule work but must not mutate entities directly.
            close(true);
        }

        private void close(boolean retired) {
            if (!closed.compareAndSet(false, true)) return;
            flights.remove(trident.getUniqueId(), this);
            if (flightTask != null) flightTask.cancel();
            if (displayTask != null) displayTask.cancel();
            onOwner(trident, () -> trident.setVisibleByDefault(visible), retired);
            onOwner(display, display::remove, retired);
        }
    }

    private static void onOwner(Entity entity, Runnable action, boolean defer) {
        if (!defer && Bukkit.isOwnedByCurrentRegion(entity)) action.run();
        else if (OraxenPlugin.get().isEnabled()) SchedulerUtil.runForEntity(entity, action);
    }
}
