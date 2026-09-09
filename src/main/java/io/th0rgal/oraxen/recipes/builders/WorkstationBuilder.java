package io.th0rgal.oraxen.recipes.builders;

import io.th0rgal.oraxen.utils.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public abstract class WorkstationBuilder extends RecipeBuilder {

    private final String valueKey;
    private int value;

    protected WorkstationBuilder(Player player, String builderName, String valueKey) {
        super(player, builderName);
        this.valueKey = valueKey;
    }

    @Override
    public void saveRecipe(String name) {
        saveRecipe(name, null);
    }

    @Override
    public void saveRecipe(String name, String permission) {
        ItemStack[] content = getInventory().getContents();
        ConfigurationSection newCraftSection = getConfig().createSection(name);

        setSerializedItem(newCraftSection.createSection("base"), content[0]);
        if (!ItemUtils.isEmpty(content[1]))
            setSerializedItem(newCraftSection.createSection("addition"), content[1]);
        setSerializedItem(newCraftSection.createSection("result"), content[2]);
        newCraftSection.set(valueKey, value);

        if (permission != null && !permission.isEmpty())
            newCraftSection.set("permission", permission);

        saveConfig();
        close();
    }

    protected void setValue(int value) {
        this.value = Math.max(0, value);
    }
}
