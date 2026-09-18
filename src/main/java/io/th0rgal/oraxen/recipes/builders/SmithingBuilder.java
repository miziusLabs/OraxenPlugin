package io.th0rgal.oraxen.recipes.builders;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class SmithingBuilder extends RecipeBuilder {

    public SmithingBuilder(Player player) {
        super(player, "smithing");
    }

    @Override
    Inventory createInventory(Player player, Component inventoryTitle) {
        return Bukkit.createInventory(player, InventoryType.SMITHING, inventoryTitle);
    }

    @Override
    public void saveRecipe(String name) {
        saveRecipe(name, null);
    }

    @Override
    public void saveRecipe(String name, String permission) {
        ItemStack[] content = getInventory().getContents();
        ConfigurationSection newCraftSection = getConfig().createSection(name);

        setSingleIngredient(newCraftSection.createSection("template"), content[0]);
        setSingleIngredient(newCraftSection.createSection("base"), content[1]);
        setSingleIngredient(newCraftSection.createSection("addition"), content[2]);
        setSerializedItem(newCraftSection.createSection("result"), content[3]);

        if (permission != null && !permission.isEmpty())
            newCraftSection.set("permission", permission);

        saveConfig();
        close();
    }

    private void setSingleIngredient(ConfigurationSection section, ItemStack itemStack) {
        ItemStack singleItem = itemStack.clone();
        singleItem.setAmount(1);
        setSerializedItem(section, singleItem);
    }
}
