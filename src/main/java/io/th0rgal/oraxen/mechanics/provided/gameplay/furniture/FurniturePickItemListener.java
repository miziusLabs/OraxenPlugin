package io.th0rgal.oraxen.mechanics.provided.gameplay.furniture;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import io.th0rgal.oraxen.api.OraxenFurniture;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.items.ItemBuilder;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public class FurniturePickItemListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPickBlock(PlayerPickBlockEvent event) {
        handlePick(event, OraxenFurniture.getFurnitureMechanic(event.getBlock()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickEntity(PlayerPickEntityEvent event) {
        handlePick(event, OraxenFurniture.getFurnitureMechanic(event.getEntity()));
    }

    private void handlePick(PlayerPickItemEvent event, FurnitureMechanic mechanic) {
        if (mechanic == null) return;

        event.setCancelled(true);
        ItemBuilder itemBuilder = OraxenItems.getItemById(mechanic.getItemID());
        if (itemBuilder == null) return;

        pickItem(event.getPlayer(), itemBuilder.build(), event.getTargetSlot());
    }

    private void pickItem(Player player, ItemStack item, int targetSlot) {
        if (item == null || item.getType().isAir()) return;

        PlayerInventory inventory = player.getInventory();
        int sourceSlot = findMatchingSlot(inventory, item);
        if (sourceSlot >= 0) {
            if (sourceSlot != targetSlot) {
                ItemStack targetItem = inventory.getItem(targetSlot);
                inventory.setItem(targetSlot, inventory.getItem(sourceSlot));
                inventory.setItem(sourceSlot, targetItem);
            }
            inventory.setHeldItemSlot(targetSlot);
            return;
        }

        if (player.getGameMode() != GameMode.CREATIVE) return;

        ItemStack targetItem = inventory.getItem(targetSlot);
        if (targetItem != null && !targetItem.getType().isAir()) {
            int emptySlot = findEmptySlot(inventory, targetSlot);
            if (emptySlot >= 0) inventory.setItem(emptySlot, targetItem);
        }

        inventory.setItem(targetSlot, item);
        inventory.setHeldItemSlot(targetSlot);
    }

    private int findMatchingSlot(PlayerInventory inventory, ItemStack item) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (candidate != null && candidate.isSimilar(item)) return slot;
        }
        return -1;
    }

    private int findEmptySlot(PlayerInventory inventory, int excludedSlot) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot == excludedSlot) continue;
            ItemStack candidate = inventory.getItem(slot);
            if (candidate == null || candidate.getType().isAir()) return slot;
        }
        return -1;
    }
}
