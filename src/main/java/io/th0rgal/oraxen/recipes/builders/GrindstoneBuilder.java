package io.th0rgal.oraxen.recipes.builders;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

public class GrindstoneBuilder extends WorkstationBuilder {

    public GrindstoneBuilder(Player player) {
        super(player, "grindstone", "experience");
    }

    @Override
    Inventory createInventory(Player player, Component inventoryTitle) {
        return Bukkit.createInventory(player, InventoryType.GRINDSTONE, inventoryTitle);
    }

    public void setExperience(int experience) {
        setValue(experience);
    }
}
