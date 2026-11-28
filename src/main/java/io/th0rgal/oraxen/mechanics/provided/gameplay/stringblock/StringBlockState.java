package io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock;

import com.jeff_media.morepersistentdatatypes.DataType;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.utils.BlockHelpers;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Persistent identity and display state for custom string blocks.
 * The visible model is rendered by an ItemDisplay while STRUCTURE_VOID provides the hitbox.
 */
public final class StringBlockState {

    public static final Material MATERIAL = Material.STRUCTURE_VOID;
    public static final int LEGACY_MODEL_DATA_BASE = 16_000_000;

    private static final NamespacedKey variationKey = new NamespacedKey("oraxen", "string_block_variation");
    private static final NamespacedKey upperKey = new NamespacedKey("oraxen", "string_block_upper");
    private static final NamespacedKey displayKey = new NamespacedKey("oraxen", "string_block_display");
    private static final NamespacedKey fallingKey = new NamespacedKey("oraxen", "falling_string_block");
    private static final NamespacedKey fallingDisplayKey = new NamespacedKey("oraxen", "string_block_falling_display");

    private StringBlockState() {
    }

    public static int variation(Block block) {
        if (block.getType() != MATERIAL || !BlockHelpers.hasPDC(block, OraxenPlugin.get())) return 0;
        return BlockHelpers.getPDC(block).getOrDefault(variationKey, PersistentDataType.INTEGER, 0);
    }

    public static @Nullable Block base(Block block) {
        if (block.getType() != MATERIAL || !BlockHelpers.hasPDC(block, OraxenPlugin.get())) return null;
        var pdc = BlockHelpers.getPDC(block);
        if (pdc.has(variationKey, PersistentDataType.INTEGER)) return block;
        if (!pdc.has(upperKey, PersistentDataType.BYTE)) return null;
        Block below = block.getRelative(BlockFace.DOWN);
        StringBlockMechanic mechanic = StringBlockMechanicFactory.getBlockMechanic(variation(below));
        return mechanic != null && mechanic.isTall() ? below : null;
    }

    /**
     * Checks only the Oraxen string-block identity keys. The block type is deliberately
     * not part of this check so replacement handlers can recognize a replaced carrier.
     */
    public static boolean hasMarker(Block block) {
        if (!BlockHelpers.hasPDC(block, OraxenPlugin.get())) return false;
        PersistentDataContainer pdc = BlockHelpers.getPDC(block);
        return pdc.has(variationKey, PersistentDataType.INTEGER) || pdc.has(upperKey, PersistentDataType.BYTE);
    }

    public static void mark(Block block, int variation) {
        BlockHelpers.getPDC(block).set(variationKey, PersistentDataType.INTEGER, variation);
    }

    public static void markUpper(Block block) {
        BlockHelpers.getPDC(block).set(upperKey, PersistentDataType.BYTE, (byte) 1);
    }

    public static void unmark(Block block) {
        PersistentDataContainer pdc = BlockHelpers.getPDC(block);
        pdc.remove(variationKey);
        pdc.remove(upperKey);
    }

    public static void place(Block block, int variation, boolean tall) {
        if (!Bukkit.isOwnedByCurrentRegion(block)) {
            SchedulerUtil.runAtLocation(block.getLocation(), () -> place(block, variation, tall));
            return;
        }
        block.setType(MATERIAL, false);
        mark(block, variation);
        if (tall) {
            Block upper = block.getRelative(BlockFace.UP);
            upper.setType(MATERIAL, false);
            markUpper(upper);
        }
        updateDisplay(block, variation);
    }

    public static ItemStack modelItem(int variation) {
        ItemStack item = new ItemStack(Material.STRUCTURE_VOID);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setCustomModelData(LEGACY_MODEL_DATA_BASE + variation);
        if (VersionUtil.atOrAbove("1.21.4"))
            meta.setItemModel(new NamespacedKey("oraxen_string_blocks", Integer.toString(variation)));
        item.setItemMeta(meta);
        return item;
    }

    public static void updateDisplay(Block block, int variation) {
        if (!Bukkit.isOwnedByCurrentRegion(block)) {
            SchedulerUtil.runAtLocation(block.getLocation(), () -> updateDisplay(block, variation));
            return;
        }

        ItemStack model = modelItem(variation);
        ItemDisplay existing = display(block);
        if (existing != null && isAtBlock(existing, block)) {
            updateDisplayEntity(existing, model);
            return;
        }
        if (existing != null) removeEntityOnOwningThread(existing);

        Location location = block.getLocation().add(0.5, 0.5, 0.5);
        ItemDisplay display = block.getWorld().spawn(location, ItemDisplay.class, entity -> {
            updateDisplayEntity(entity, model);
            entity.setPersistent(true);
        });
        BlockHelpers.getPDC(block).set(displayKey, DataType.UUID, display.getUniqueId());
    }

    private static void updateDisplayEntity(ItemDisplay display, ItemStack model) {
        display.setItemStack(model);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        display.setRotation(180f, 0f);
        display.setDisplayWidth(2f);
        display.setDisplayHeight(2f);
        display.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte) 1);
    }

    public static void clearDisplay(Block block) {
        if (!BlockHelpers.hasPDC(block, OraxenPlugin.get())) return;
        PersistentDataContainer pdc = BlockHelpers.getPDC(block);
        UUID uuid = pdc.get(displayKey, DataType.UUID);
        if (uuid != null) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity instanceof ItemDisplay display && isMarkedDisplay(display))
                removeEntityOnOwningThread(display);
        }
        pdc.remove(displayKey);
    }

    public static void clearUpper(Block base) {
        if (base.getY() + 1 >= base.getWorld().getMaxHeight()) return;
        Block upper = base.getRelative(BlockFace.UP);
        if (upper.getType() != MATERIAL || !BlockHelpers.hasPDC(upper, OraxenPlugin.get())) return;
        PersistentDataContainer pdc = BlockHelpers.getPDC(upper);
        if (!pdc.has(upperKey, PersistentDataType.BYTE)) return;
        Block resolvedBase = base(upper);
        if (resolvedBase == null || !resolvedBase.equals(base)) return;
        upper.setType(Material.AIR, false);
        BlockHelpers.removePDC(upper);
    }

    /**
     * Clears a marked upper after its base carrier was already replaced externally.
     * The upper marker is positional, so this never changes an unmarked block or tripwire.
     */
    public static void clearOrphanedUpper(Block removedBase) {
        if (removedBase.getY() + 1 >= removedBase.getWorld().getMaxHeight()) return;
        Block upper = removedBase.getRelative(BlockFace.UP);
        if (upper.getType() != MATERIAL || !BlockHelpers.hasPDC(upper, OraxenPlugin.get())) return;
        if (!BlockHelpers.getPDC(upper).has(upperKey, PersistentDataType.BYTE)) return;
        upper.setType(Material.AIR, false);
        BlockHelpers.removePDC(upper);
    }

    public static void spawnFalling(Block block, int variation) {
        Location center = BlockHelpers.toCenterBlockLocation(block.getLocation());
        FallingBlock falling = block.getWorld().spawn(center, FallingBlock.class, entity -> {
            entity.setBlockData(MATERIAL.createBlockData());
            entity.setDropItem(false);
            entity.getPersistentDataContainer().set(fallingKey, PersistentDataType.INTEGER, variation);
        });
        ItemDisplay display = block.getWorld().spawn(center, ItemDisplay.class, passenger -> {
            updateDisplayEntity(passenger, modelItem(variation));
            passenger.setPersistent(true);
            passenger.getPersistentDataContainer().set(fallingDisplayKey, PersistentDataType.BYTE, (byte) 1);
        });
        if (falling.addPassenger(display)) scheduleFallingDisplayCleanup(display);
        else display.remove();
    }

    static void scheduleFallingDisplayCleanup(ItemDisplay display) {
        display.getScheduler().runAtFixedRate(OraxenPlugin.get(), task -> {
            if (display.getVehicle() instanceof FallingBlock) return;
            display.remove();
            task.cancel();
        }, null, 1L, 10L);
    }

    public static int fallingVariation(FallingBlock fallingBlock) {
        return fallingBlock.getPersistentDataContainer().getOrDefault(fallingKey, PersistentDataType.INTEGER, 0);
    }

    public static void clearFallingDisplay(FallingBlock fallingBlock) {
        for (Entity passenger : fallingBlock.getPassengers()) {
            if (passenger instanceof ItemDisplay display && isMarkedDisplay(display)) display.remove();
        }
    }

    public static boolean isMarkedDisplay(ItemDisplay display) {
        return display.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE);
    }

    public static boolean isFallingDisplay(ItemDisplay display) {
        return display.getPersistentDataContainer().has(fallingDisplayKey, PersistentDataType.BYTE);
    }

    public static boolean isDisplayFor(Block block, ItemDisplay display) {
        if (variation(block) == 0 || !isMarkedDisplay(display) || !isAtBlock(display, block)) return false;
        if (!BlockHelpers.hasPDC(block, OraxenPlugin.get())) return false;
        UUID uuid = BlockHelpers.getPDC(block).get(displayKey, DataType.UUID);
        return display.getUniqueId().equals(uuid);
    }

    /**
     * Clears only the stale identity signed by this exact display after the carrier was
     * externally replaced. A newer valid display UUID is deliberately left untouched.
     */
    public static void clearStaleDisplay(Block block, ItemDisplay display) {
        if (!BlockHelpers.hasPDC(block, OraxenPlugin.get())) return;
        PersistentDataContainer pdc = BlockHelpers.getPDC(block);
        if (!pdc.has(displayKey, DataType.UUID) || block.getType() == MATERIAL) return;
        UUID uuid = pdc.get(displayKey, DataType.UUID);
        if (!display.getUniqueId().equals(uuid)) return;
        clearOrphanedUpper(block);
        unmark(block);
        pdc.remove(displayKey);
    }

    public static boolean migrateLegacy(Block block) {
        if (!(block.getBlockData() instanceof Tripwire tripwire)) return false;
        int variation = StringBlockMechanicFactory.getCode(tripwire);
        StringBlockMechanic mechanic = StringBlockMechanicFactory.getBlockMechanic(variation);
        if (mechanic == null) return false;

        if (mechanic.isTall()) {
            Block upper = block.getRelative(BlockFace.UP);
            if (upper.getY() >= block.getWorld().getMaxHeight()) return false;
            if (upper.getBlockData() instanceof Tripwire upperTripwire) {
                if (StringBlockMechanicFactory.getCode(upperTripwire) != 0) return false;
            } else if (!BlockHelpers.isReplaceable(upper.getType())) {
                return false;
            }
        }

        place(block, variation, mechanic.isTall());
        return true;
    }

    private static @Nullable ItemDisplay display(Block block) {
        if (!BlockHelpers.hasPDC(block, OraxenPlugin.get())) return null;
        UUID uuid = BlockHelpers.getPDC(block).get(displayKey, DataType.UUID);
        if (uuid == null) return null;
        Entity entity = Bukkit.getEntity(uuid);
        return entity instanceof ItemDisplay display && isMarkedDisplay(display) ? display : null;
    }

    private static boolean isAtBlock(ItemDisplay display, Block block) {
        Location location = display.getLocation();
        return location.getWorld() != null && location.getWorld().equals(block.getWorld())
                && location.getBlockX() == block.getX()
                && location.getBlockY() == block.getY()
                && location.getBlockZ() == block.getZ();
    }

    private static void removeEntityOnOwningThread(Entity entity) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) entity.remove();
        else SchedulerUtil.runOnOwningThread(entity, entity::remove);
    }
}
