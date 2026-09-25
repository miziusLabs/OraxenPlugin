package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.mechanics.provided.misc.invulnerable.InvulnerableListener;
import io.th0rgal.oraxen.mechanics.provided.misc.invulnerable.InvulnerableMechanic;
import io.th0rgal.oraxen.mechanics.provided.misc.invulnerable.InvulnerableMechanicFactory;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
}
