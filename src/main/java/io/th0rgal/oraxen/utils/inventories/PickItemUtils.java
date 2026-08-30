package io.th0rgal.oraxen.utils.inventories;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class PickItemUtils {

    private PickItemUtils() {
    }

    public static void pickItem(Player player, ItemStack item, int targetSlot) {
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

    private static int findMatchingSlot(PlayerInventory inventory, ItemStack item) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (candidate != null && candidate.isSimilar(item)) return slot;
        }
        return -1;
    }

    private static int findEmptySlot(PlayerInventory inventory, int excludedSlot) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot == excludedSlot) continue;
            ItemStack candidate = inventory.getItem(slot);
            if (candidate == null || candidate.getType().isAir()) return slot;
        }
        return -1;
    }
}
