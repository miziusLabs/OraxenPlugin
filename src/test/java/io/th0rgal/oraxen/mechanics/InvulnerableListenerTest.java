package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.mechanics.provided.misc.invulnerable.InvulnerableListener;
import io.th0rgal.oraxen.mechanics.provided.misc.invulnerable.InvulnerableMechanic;
import io.th0rgal.oraxen.mechanics.provided.misc.invulnerable.InvulnerableMechanicFactory;
import io.th0rgal.oraxen.utils.VersionUtil;
import net.kyori.adventure.util.TriState;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvulnerableListenerTest {

    @Test
    void cancelsOnlyConfiguredDamageForDroppedItems() {
        ItemStack stack = mock(ItemStack.class);
        Item item = mock(Item.class);
        when(item.getItemStack()).thenReturn(stack);
        InvulnerableMechanicFactory factory = mock(InvulnerableMechanicFactory.class);
        InvulnerableMechanic mechanic = mock(InvulnerableMechanic.class);
        when(factory.getMechanic(stack)).thenReturn(mechanic);
        when(mechanic.protectsFrom(EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)).thenReturn(true);

        EntityDamageEvent protectedEvent = mock(EntityDamageEvent.class);
        when(protectedEvent.getEntity()).thenReturn(item);
        when(protectedEvent.getCause()).thenReturn(EntityDamageEvent.DamageCause.BLOCK_EXPLOSION);
        EntityDamageEvent otherEvent = mock(EntityDamageEvent.class);
        when(otherEvent.getEntity()).thenReturn(item);
        when(otherEvent.getCause()).thenReturn(EntityDamageEvent.DamageCause.VOID);

        try (MockedStatic<InvulnerableMechanicFactory> factories = mockStatic(InvulnerableMechanicFactory.class)) {
            factories.when(InvulnerableMechanicFactory::get).thenReturn(factory);
            InvulnerableListener listener = new InvulnerableListener();
            listener.onItemDamage(protectedEvent);
            listener.onItemDamage(otherEvent);
        }

        verify(protectedEvent).setCancelled(true);
        verify(otherEvent, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void hidesFireOnSpawnForProtectedItemsOnModernServers() {
        ItemStack stack = mock(ItemStack.class);
        Item item = mock(Item.class);
        when(item.getItemStack()).thenReturn(stack);
        ItemSpawnEvent event = mock(ItemSpawnEvent.class);
        when(event.getEntity()).thenReturn(item);
        InvulnerableMechanicFactory factory = mock(InvulnerableMechanicFactory.class);
        InvulnerableMechanic mechanic = mock(InvulnerableMechanic.class);
        when(factory.getMechanic(stack)).thenReturn(mechanic);
        when(mechanic.protectsFrom(EntityDamageEvent.DamageCause.LAVA)).thenReturn(true);

        try (MockedStatic<InvulnerableMechanicFactory> factories = mockStatic(InvulnerableMechanicFactory.class);
             MockedStatic<VersionUtil> versions = mockStatic(VersionUtil.class)) {
            factories.when(InvulnerableMechanicFactory::get).thenReturn(factory);
            versions.when(() -> VersionUtil.atOrAbove("1.21.5")).thenReturn(true);
            new InvulnerableListener().onItemSpawn(event);
        }

        verify(item).setVisualFire(TriState.FALSE);
    }

    @Test
    void keepsOldServersAwayFromVisualFireApi() {
        ItemSpawnEvent event = mock(ItemSpawnEvent.class);

        try (MockedStatic<VersionUtil> versions = mockStatic(VersionUtil.class)) {
            versions.when(() -> VersionUtil.atOrAbove("1.21.5")).thenReturn(false);
            new InvulnerableListener().onItemSpawn(event);
        }

        verify(event, never()).getEntity();
    }

    @Test
    void hidesFireWhenCancellingFireDamageForExistingItems() {
        ItemStack stack = mock(ItemStack.class);
        Item item = mock(Item.class);
        when(item.getItemStack()).thenReturn(stack);
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(item);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FIRE_TICK);
        InvulnerableMechanicFactory factory = mock(InvulnerableMechanicFactory.class);
        InvulnerableMechanic mechanic = mock(InvulnerableMechanic.class);
        when(factory.getMechanic(stack)).thenReturn(mechanic);
        when(mechanic.protectsFrom(EntityDamageEvent.DamageCause.FIRE_TICK)).thenReturn(true);

        try (MockedStatic<InvulnerableMechanicFactory> factories = mockStatic(InvulnerableMechanicFactory.class);
             MockedStatic<VersionUtil> versions = mockStatic(VersionUtil.class)) {
            factories.when(InvulnerableMechanicFactory::get).thenReturn(factory);
            versions.when(() -> VersionUtil.atOrAbove("1.21.5")).thenReturn(true);
            new InvulnerableListener().onItemDamage(event);
        }

        verify(event).setCancelled(true);
        verify(item).setVisualFire(TriState.FALSE);
    }
}
