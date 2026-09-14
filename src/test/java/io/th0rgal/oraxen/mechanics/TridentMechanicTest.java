package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.mechanics.provided.combat.trident.TridentMechanic;
import io.th0rgal.oraxen.mechanics.provided.combat.trident.TridentMechanicFactory;
import io.th0rgal.oraxen.mechanics.provided.combat.trident.TridentMechanicListener;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TridentMechanicTest extends MechanicTestSupport {
    @Test
    void parsesRequestedConfiguration() {
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), mechanicSection("trident",
                "sounds.throw", "trident.throw.sound", "sounds.hit", "trident.hit.sound",
                "sounds.hit-ground", "trident.ground.sound", "sounds.return", "trident.return.sound",
                "appearance.model", "tridents/oraxen_trident",
                "appearance.thrown-model", "tridents/oraxen_trident_thrown",
                "appearance.transform", "FIXED"));
        assertEquals("trident.throw.sound", mechanic.getThrowSound());
        assertEquals("trident.hit.sound", mechanic.getHitSound());
        assertEquals("trident.ground.sound", mechanic.getHitGroundSound());
        assertEquals("trident.return.sound", mechanic.getReturnSound());
        assertEquals("tridents/oraxen_trident", mechanic.getModel());
        assertEquals("tridents/oraxen_trident_thrown", mechanic.getThrownModel());
        assertEquals(ItemDisplayTransform.FIXED, mechanic.getTransform());
    }

    @Test
    void fallsBackToConfiguredSoundsAndDefaultTransform() {
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), mechanicSection("trident",
                "sounds.throw", "custom.throw", "sounds.hit", "custom.hit"));
        assertEquals("custom.throw", mechanic.getReturnSound());
        assertEquals("custom.hit", mechanic.getHitGroundSound());
        assertEquals(ItemDisplayTransform.NONE, mechanic.getTransform());
    }

    @Test
    void supportsSoundOnlyTridentsAndVanillaDefaults() {
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), mechanicSection("trident"));
        assertNull(mechanic.getDisplayItem());
        assertEquals("minecraft:item.trident.throw", mechanic.getThrowSound());
        assertEquals(mechanic.getThrowSound(), mechanic.getReturnSound());
        assertEquals(mechanic.getHitSound(), mechanic.getHitGroundSound());
    }

    @Test
    void rejectsInvalidTransformWithItemContext() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new TridentMechanic(mechanicFactory(), mechanicSection("trident",
                        "appearance.transform", "invalid")));
        assertTrue(error.getMessage().contains("test_item"));
    }

    @Test
    void distinguishesEntityAndGroundImpactWithoutReadingTargetRegion() {
        TridentMechanicFactory factory = mock(TridentMechanicFactory.class);
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), mechanicSection("trident",
                "sounds.hit", "custom.entity", "sounds.hit-ground", "custom.ground"));
        Trident trident = mock(Trident.class);
        ItemStack item = mock(ItemStack.class);
        World world = mock(World.class);
        Location location = new Location(world, 1, 2, 3);
        when(trident.getItemStack()).thenReturn(item);
        when(trident.getLocation()).thenReturn(location);
        when(factory.getMechanic(item)).thenReturn(mechanic);
        ProjectileHitEvent event = mock(ProjectileHitEvent.class);
        when(event.getEntity()).thenReturn(trident);
        Entity target = mock(Entity.class);
        when(event.getHitEntity()).thenReturn(target);
        TridentMechanicListener listener = new TridentMechanicListener(factory);
        listener.onHit(event);
        verify(world).playSound(location, "custom.entity", 1f, 1f);
        verifyNoInteractions(target);
        when(event.getHitEntity()).thenReturn(null);
        Block block = mock(Block.class);
        when(event.getHitBlock()).thenReturn(block);
        listener.onHit(event);
        verify(world).playSound(location, "custom.ground", 1f, 1f);
        verifyNoInteractions(block);
    }
    @Test
    @SuppressWarnings("unchecked")
    void createsOneDisplayAndRemovesItOnItsOwnSchedulerAfterProjectileRemoval() {
        TridentMechanicFactory factory = mock(TridentMechanicFactory.class);
        TridentMechanic mechanic = mock(TridentMechanic.class);
        var displayItem = mock(io.th0rgal.oraxen.items.ItemBuilder.class);
        when(mechanic.getDisplayItem()).thenReturn(displayItem);
        when(mechanic.createDisplayStack()).thenReturn(mock(ItemStack.class));
        Trident trident = mock(Trident.class);
        java.util.UUID id = java.util.UUID.randomUUID();
        when(trident.getUniqueId()).thenReturn(id);
        ItemStack item = mock(ItemStack.class);
        when(trident.getItemStack()).thenReturn(item);
        when(factory.getMechanic(item)).thenReturn(mechanic);
        World world = mock(World.class);
        when(trident.getWorld()).thenReturn(world);
        when(trident.getLocation()).thenReturn(new Location(world, 1, 2, 3));
        org.bukkit.entity.ItemDisplay display = mock(org.bukkit.entity.ItemDisplay.class);
        when(world.spawn(any(Location.class), eq(org.bukkit.entity.ItemDisplay.class), any(java.util.function.Consumer.class)))
                .thenReturn(display);
        var projectileScheduler = mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        var displayScheduler = mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(trident.getScheduler()).thenReturn(projectileScheduler);
        when(display.getScheduler()).thenReturn(displayScheduler);
        var scheduledTask = mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
        when(projectileScheduler.runAtFixedRate(any(), any(), any(), eq(1L), eq(1L))).thenReturn(scheduledTask);
        when(displayScheduler.runAtFixedRate(any(), any(), any(), eq(1L), eq(1L))).thenReturn(scheduledTask);
        TridentMechanicListener listener = new TridentMechanicListener(factory);
        var add = mock(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent.class);
        when(add.getEntity()).thenReturn(trident);
        listener.onAdd(add);
        listener.onAdd(add);
        verify(world, times(1)).spawn(any(Location.class), eq(org.bukkit.entity.ItemDisplay.class), any(java.util.function.Consumer.class));
        var track = mock(io.papermc.paper.event.player.PlayerTrackEntityEvent.class);
        when(track.getEntity()).thenReturn(trident);
        listener.onTrack(track);
        verify(track).setCancelled(true);
        var remove = mock(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent.class);
        when(remove.getEntity()).thenReturn(trident);
        listener.onRemove(remove);
        verify(display, never()).remove();
        org.mockito.ArgumentCaptor<java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>> tick =
                org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(displayScheduler).runAtFixedRate(any(), tick.capture(), any(), eq(1L), eq(1L));
        tick.getValue().accept(scheduledTask);
        verify(display).remove();
        verify(scheduledTask).cancel();
        verify(trident, never()).remove();
        verify(trident, never()).setItemStack(any());
    }

}
