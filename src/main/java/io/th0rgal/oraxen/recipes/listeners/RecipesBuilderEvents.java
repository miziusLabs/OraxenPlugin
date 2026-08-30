package io.th0rgal.oraxen.recipes.listeners;

import io.th0rgal.oraxen.recipes.builders.RecipeBuilder;
import io.th0rgal.oraxen.utils.InventoryUtils;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

public class RecipesBuilderEvents implements Listener {

    static boolean isBuilderInventory(InventoryEvent event, Class<? extends RecipeBuilder> builderType) {
        Player player = InventoryUtils.playerFromView(event);
        RecipeBuilder recipeBuilder = player == null ? null : RecipeBuilder.get(player.getUniqueId());
        return builderType.isInstance(recipeBuilder)
                && recipeBuilder.matchesInventory(event.getInventory());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void setCursor(InventoryClickEvent event) {
        RecipeBuilder recipeBuilder = RecipeBuilder.get(event.getWhoClicked().getUniqueId());
        if (recipeBuilder == null || !recipeBuilder.matchesInventory(event.getInventory())
                || event.getSlotType() != InventoryType.SlotType.RESULT) return;

        event.setCancelled(true);
        ItemStack currentResult =  Optional.ofNullable(event.getCurrentItem()).orElse(new ItemStack(Material.AIR)).clone();
        ItemStack currentCursor = Optional.ofNullable(event.getCursor()).orElse(new ItemStack(Material.AIR)).clone();
        event.setCurrentItem(currentCursor);
        event.getView().setCursor(currentResult);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClosed(InventoryCloseEvent event) {
        RecipeBuilder recipeBuilder = RecipeBuilder.get(event.getPlayer().getUniqueId());
        if (recipeBuilder == null || !recipeBuilder.matchesInventory(event.getInventory()))
            return;

        recipeBuilder.setInventory(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        RecipeBuilder.remove(event.getPlayer().getUniqueId());
    }
}
