package io.th0rgal.oraxen.mechanics.provided.farming.smelting;

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.utils.wrappers.EnchantmentWrapper;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class SmeltingMechanicListener implements Listener {

    private final MechanicFactory factory;

    public SmeltingMechanicListener(MechanicFactory factory) {
        this.factory = factory;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        String itemID = OraxenItems.getIdByItem(item);

        if (factory.isNotImplementedIn(itemID)) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta == null) return;

        Block block = event.getBlock();
        boolean fortune = block.getType().toString().contains("ORE") && itemMeta.hasEnchant(EnchantmentWrapper.FORTUNE);
        List<ItemStack> drops = new ArrayList<>();
        boolean smelted = false;
        for (ItemStack drop : block.getDrops()) {
            ItemStack loot = furnace(drop);
            if (loot == null) { // not recipe
                drops.add(drop);
                continue;
            }
            if (fortune)
                loot.setAmount(1 + ThreadLocalRandom.current().nextInt(itemMeta.getEnchantLevel(EnchantmentWrapper.FORTUNE)));
            drops.add(loot);
            smelted = true;
        }
        if (!smelted) return;

        Location location = block.getLocation().toCenterLocation();
        event.setDropItems(false);
        if (!location.isWorldLoaded()) return;
        assert location.getWorld() != null;
        for (ItemStack drop : drops)
            location.getWorld().dropItemNaturally(location, drop);

        SmeltingMechanic mechanic = (SmeltingMechanic) factory.getMechanic(itemID);
        if (mechanic != null && mechanic.playSound())
            location.getWorld().playSound(location, Sound.ENTITY_GUARDIAN_ATTACK, 0.10f, 0.8f);
    }

    private ItemStack furnace(ItemStack item) {
        if (item == null)
            return null; // Because item can be null
        String type = item.getType().toString();
        if (type.startsWith("RAW_") && !type.endsWith("_BLOCK")) {
            Material smeltedMaterial = Material.matchMaterial(item.getType().toString().substring(4) + "_INGOT");
            if (smeltedMaterial == null) return null;
            item.setType(smeltedMaterial);
            return item;
        }

        for (Recipe recipe : Bukkit.getRecipesFor(item)) {
            if (!(recipe instanceof CookingRecipe<?> cookingRecipe))
                continue;
            if (cookingRecipe.getInputChoice().test(item))
                return new ItemStack(recipe.getResult().getType(), item.getAmount());
        }
        return null; // return result furnace :)
    }

}
