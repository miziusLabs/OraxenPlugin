package io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock;

import io.th0rgal.oraxen.api.OraxenBlocks;
import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.api.events.stringblock.OraxenStringBlockInteractEvent;
import io.th0rgal.oraxen.api.events.stringblock.OraxenStringBlockPlaceEvent;
import io.th0rgal.oraxen.mechanics.provided.gameplay.limitedplacing.LimitedPlacing;
import io.th0rgal.oraxen.mechanics.provided.gameplay.storage.StorageMechanic;
import io.th0rgal.oraxen.protection.AntiGriefLib;
import io.th0rgal.oraxen.utils.BlockHelpers;
import io.th0rgal.oraxen.utils.ItemUtils;
import io.th0rgal.oraxen.utils.Utils;
import io.th0rgal.oraxen.utils.VersionUtil;
import io.th0rgal.oraxen.utils.breaker.BreakerSystem;
import io.th0rgal.oraxen.utils.breaker.HardnessModifier;
import org.apache.commons.lang3.Range;
import org.bukkit.Bukkit;
import org.bukkit.ExplosionResult;
import org.bukkit.GameEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public class StringBlockMechanicListener implements Listener {

    public StringBlockMechanicListener() {
        // BreakerSystem no longer requires a packet library, register unconditionally.
        BreakerSystem.registerModifier(StringBlockMechanicListener.class, getHardnessModifier());
    }

    private HardnessModifier getHardnessModifier() {
        return new HardnessModifier() {
            @Override
            public boolean isTriggered(final Player player, final Block block, final ItemStack tool) {
                if (block.getType() != StringBlockState.MATERIAL) return false;
                final StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(block);
                return mechanic != null && mechanic.hasHardness(tool);
            }

            @Override
            public void breakBlock(final Player player, final Block block, final ItemStack tool) {
                block.setType(Material.AIR);
            }

            @Override
            public long getPeriod(final Player player, final Block block, final ItemStack tool) {
                final StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(block);
                if (mechanic == null) return 0;
                final double hardness = mechanic.getHardness(tool);
                long period = Math.round(hardness * 0.4D / mechanic.getPacketSpeedMultiplier(tool, block.getType()));
                return period == 0 && mechanic.hasHardness(tool) ? 1 : period;
            }
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePiston(event, event.getBlocks());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePiston(event, event.getBlocks());
    }

    private void handlePiston(BlockPistonEvent event, List<Block> blocks) {
        Set<Block> bases = new LinkedHashSet<>();
        for (Block block : blocks) {
            Block base = StringBlockState.base(block);
            if (base != null) bases.add(base);
        }
        if (bases.isEmpty()) return;

        for (Block base : bases) {
            StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(base);
            if (mechanic != null && mechanic.isImmovable()) {
                event.setCancelled(true);
                return;
            }
        }

        for (Block base : bases) {
            OraxenBlocks.remove(base.getLocation(), null, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void callInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = StringBlockState.base(clicked);
        if (block == null) return;
        StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(block);
        if (mechanic == null) return;

        OraxenStringBlockInteractEvent interactEvent = new OraxenStringBlockInteractEvent(
                mechanic, event.getPlayer(), event.getItem(),
                event.getHand(), block, event.getBlockFace());
        if (!interactEvent.callEvent()) {
            event.setCancelled(true);
            return;
        }
        // Vanilla only bypasses container/interaction handling when sneaking with an item
        // in either hand; sneaking with empty hands still opens custom storage.
        if (event.getPlayer().isSneaking() && ItemUtils.hasItemInAnyHand(event.getPlayer())) return;

        // Handle stackable block interaction: right-click with the same item to stack
        if (mechanic.isStackable() && event.getItem() != null) {
            String heldItemId = OraxenItems.getIdByItem(event.getItem());
            if (heldItemId != null && heldItemId.equals(mechanic.getItemID())) {
                if (handleStackInteraction(mechanic, block, event.getPlayer(), event.getItem(), event.getHand())) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // Handle storage interaction
        if (mechanic.isStorage()) {
            handleStorageInteraction(mechanic, block, event.getPlayer());
            event.setCancelled(true);
        }
    }

    /**
     * Handles stacking a block to its next stack level.
     * Returns true if stacking handled the interaction, false if already at max level.
     */
    private boolean handleStackInteraction(StringBlockMechanic mechanic, Block block, Player player, ItemStack item,
                                           EquipmentSlot hand) {
        int currentVariation = StringBlockState.variation(block);
        var nextStack = mechanic.getNextStackVariation(currentVariation);
        if (nextStack == null) return false;
        if (!AntiGriefLib.canBuild(player, block.getLocation())) return true;

        var placeEvent = new BlockPlaceEvent(block, block.getState(), block, item, player, true, hand);
        if (!placeEvent.callEvent() || !placeEvent.canBuild()) return true;
        var oraxenPlaceEvent = new OraxenStringBlockPlaceEvent(mechanic, block, player, item, hand);
        if (!oraxenPlaceEvent.callEvent()) return true;

        StringBlockState.mark(block, nextStack.customVariation());
        StringBlockState.updateDisplay(block, nextStack.customVariation());

        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }

        // Play placement sound if configured
        if (mechanic.hasBlockSounds() && mechanic.getBlockSounds().hasPlaceSound()) {
            BlockHelpers.playCustomBlockSound(block.getLocation(),
                    mechanic.getBlockSounds().getPlaceSound(),
                    mechanic.getBlockSounds().getPlaceVolume(),
                    mechanic.getBlockSounds().getPlacePitch());
        }

        mechanic.runPlaceEvents(player);
        return true;
    }

    private void handleStorageInteraction(StringBlockMechanic mechanic, Block block, Player player) {
        StorageMechanic storage = mechanic.getStorage();
        if (storage == null) return;

        switch (storage.getStorageType()) {
            case STORAGE, SHULKER -> storage.openStorage(block, player);
            case PERSONAL -> storage.openPersonalStorage(player, block.getLocation(), null);
            case DISPOSAL -> storage.openDisposal(player, block.getLocation(), null);
            case ENDERCHEST -> player.openInventory(player.getEnderChest());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onLimitedPlacing(final PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        BlockFace blockFace = event.getBlockFace();
        ItemStack item = event.getItem();

        if (item == null || block == null || event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(OraxenItems.getIdByItem(item));
        if (mechanic == null || !mechanic.hasLimitedPlacing()) return;

        if (!event.getPlayer().isSneaking() && BlockHelpers.isInteractable(block)) return;

        LimitedPlacing limitedPlacing = mechanic.getLimitedPlacing();
        Block belowPlaced = block.getRelative(blockFace).getRelative(BlockFace.DOWN);

        if (limitedPlacing.isNotPlacableOn(block, blockFace)) {
            event.setCancelled(true);
        } else if (limitedPlacing.isRadiusLimited()) {
            LimitedPlacing.RadiusLimitation radiusLimitation = limitedPlacing.getRadiusLimitation();
            int rad = radiusLimitation.getRadius();
            int amount = radiusLimitation.getAmount();
            int count = 0;
            for (int x = -rad; x <= rad; x++)
                for (int y = -rad; y <= rad; y++)
                    for (int z = -rad; z <= rad; z++) {
                        Block relative = block.getRelative(x, y, z);
                        Location location = relative.getLocation();
                        if (!location.isChunkLoaded() || !Bukkit.isOwnedByCurrentRegion(location)) continue;
                        Block base = StringBlockState.base(relative);
                        if (!relative.equals(base)) continue;
                        StringBlockMechanic relativeMechanic = OraxenBlocks.getStringMechanic(base);
                        if (relativeMechanic == null || !relativeMechanic.getItemID().equals(mechanic.getItemID()))
                            continue;
                        count++;
                    }
            if (count >= amount) event.setCancelled(true);
        } else if (limitedPlacing.getType() == LimitedPlacing.LimitedPlacingType.ALLOW) {
            if (!limitedPlacing.checkLimitedMechanic(belowPlaced)) event.setCancelled(true);
        } else if (limitedPlacing.getType() == LimitedPlacing.LimitedPlacingType.DENY) {
            if (limitedPlacing.checkLimitedMechanic(belowPlaced)) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrePlacingCustomBlock(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        final ItemStack item = event.getItem();
        final String itemID = OraxenItems.getIdByItem(item);
        final Block placedAgainst = event.getClickedBlock();
        final Player player = event.getPlayer();
        StringBlockMechanic mechanic = StringBlockMechanicFactory.getInstance().getMechanic(itemID);

        if (mechanic == null || placedAgainst == null) return;
        if (!event.getPlayer().isSneaking() && BlockHelpers.isInteractable(placedAgainst)) return;

        StringBlockMechanic selectedMechanic = mechanic;
        if (mechanic.hasRandomPlace()) {
            List<String> randomList = mechanic.getRandomPlaceBlock();
            String randomBlock = randomList.get(new Random().nextInt(randomList.size()));
            StringBlockMechanic randomMechanic = StringBlockMechanicFactory.getInstance().getMechanic(randomBlock);
            if (randomMechanic != null) selectedMechanic = randomMechanic;
        }

        if (makePlayerPlaceBlock(player, event.getHand(), item, placedAgainst, event.getBlockFace(), selectedMechanic)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakingCustomBlock(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        if (block.getType() == Material.TRIPWIRE || block.getType() == Material.TRIPWIRE_HOOK) return;
        final Block blockAbove = block.getRelative(BlockFace.UP);
        final Player player = event.getPlayer();

        Block base = StringBlockState.base(block);
        if (base != null) {
            event.setCancelled(true);
            OraxenBlocks.remove(base.getLocation(), player);
            event.setDropItems(false);
            return;
        }

        // Handle breaking the support below a custom block. Only a marked base directly
        // above is considered; uppers never turn this into a separate removal.
        Block aboveBase = StringBlockState.base(blockAbove);
        if (aboveBase != null && aboveBase.equals(blockAbove)) {
            StringBlockMechanic aboveMechanic = OraxenBlocks.getStringMechanic(aboveBase);
            if (aboveMechanic != null && aboveMechanic.isFalling()) {
                int variation = StringBlockState.variation(aboveBase);
                if (!OraxenBlocks.remove(aboveBase.getLocation(), null)) {
                    event.setCancelled(true);
                    return;
                }

                Location fallingLocation = BlockHelpers.toCenterBlockLocation(aboveBase.getLocation());
                if (fallingLocation.getNearbyEntitiesByType(FallingBlock.class, 0.25).isEmpty())
                    StringBlockState.spawnFalling(aboveBase, variation);
                handleFallingOraxenBlockAbove(aboveBase);
                return;
            }

            if (!OraxenBlocks.remove(aboveBase.getLocation(), player)) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDestroy(EntityExplodeEvent event) {
        if (VersionUtil.atOrAbove("1.21")) {
            ExplosionResult result = event.getExplosionResult();
            if (result != ExplosionResult.DESTROY && result != ExplosionResult.DESTROY_WITH_DECAY) return;
        }
        handleExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosionDestroy(BlockExplodeEvent event) {
        if (VersionUtil.atOrAbove("1.21")) {
            ExplosionResult result = event.getExplosionResult();
            if (result != ExplosionResult.DESTROY && result != ExplosionResult.DESTROY_WITH_DECAY) return;
        }
        handleExplosion(event.blockList());
    }

    private void handleExplosion(List<Block> eventBlocks) {
        Map<Block, List<Block>> affected = new LinkedHashMap<>();
        for (Block block : new ArrayList<>(eventBlocks)) {
            Block base = StringBlockState.base(block);
            if (base == null) continue;
            affected.computeIfAbsent(base, ignored -> new ArrayList<>()).add(block);
        }

        for (Map.Entry<Block, List<Block>> entry : affected.entrySet()) {
            Block base = entry.getKey();
            StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(base);
            if (mechanic == null) continue;

            if (mechanic.isBlastResistant()) {
                eventBlocks.removeAll(entry.getValue());
                continue;
            }
            if (OraxenBlocks.remove(base.getLocation(), null, true)) eventBlocks.removeAll(entry.getValue());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWaterUpdate(final BlockFromToEvent event) {
        final Block changed = event.getToBlock();
        if (!event.getBlock().isLiquid()) return;
        Block base = StringBlockState.base(changed);
        if (base == null) return;

        event.setCancelled(true);
        OraxenBlocks.remove(base.getLocation(), null, true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMiddleClick(final InventoryCreativeEvent event) {
        if (VersionUtil.atOrAbove("1.21.5")) return;
        if (event.getClick() != ClickType.CREATIVE) return;
        final Player player = (Player) event.getInventory().getHolder();
        if (player == null) return;
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType() != StringBlockState.MATERIAL) return;

        final RayTraceResult rayTraceResult = player.rayTraceBlocks(6.0);
        if (rayTraceResult == null) return;
        final Block block = rayTraceResult.getHitBlock();
        if (block == null) return;

        StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(block);
        if (mechanic == null) return;
        ItemStack item = OraxenItems.getItemById(mechanic.getItemID()).build();
        for (int i = 0; i <= 8; i++) {
            ItemStack hotbarItem = player.getInventory().getItem(i);
            if (hotbarItem == null) continue;
            if (Objects.equals(OraxenItems.getIdByItem(hotbarItem), mechanic.getItemID())) {
                player.getInventory().setHeldItemSlot(i);
                event.setCancelled(true);
                return;
            }
        }
        event.getView().setCursor(item);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallingBlockLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock fallingBlock)) return;
        if (event.getTo() != StringBlockState.MATERIAL) return;
        int variation = StringBlockState.fallingVariation(fallingBlock);
        if (variation == 0) return;

        StringBlockState.clearFallingDisplay(fallingBlock);
        event.setCancelled(true);
        StringBlockMechanic mechanic = StringBlockMechanicFactory.getBlockMechanic(variation);
        if (mechanic != null) OraxenBlocks.placeStringBlock(event.getBlock(), mechanic, variation);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof ItemDisplay display) || !StringBlockState.isMarkedDisplay(display)) continue;
            if (StringBlockState.isFallingDisplay(display)) {
                StringBlockState.scheduleFallingDisplayCleanup(display);
                continue;
            }
            if (display.getVehicle() != null) continue;
            Block block = display.getLocation().getBlock();
            if (StringBlockState.isDisplayFor(block, display)) continue;
            StringBlockState.clearStaleDisplay(block, display);
            display.remove();
        }
    }

    private void handleFallingOraxenBlockAbove(Block block) {
        Block blockAbove = block.getRelative(BlockFace.UP);
        Block aboveBase = StringBlockState.base(blockAbove);
        if (aboveBase == null || !aboveBase.equals(blockAbove)) return;
        StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(aboveBase);
        if (mechanic == null || !mechanic.isFalling()) return;

        int variation = StringBlockState.variation(aboveBase);
        if (!OraxenBlocks.remove(aboveBase.getLocation(), null)) return;

        Location fallingLocation = BlockHelpers.toCenterBlockLocation(aboveBase.getLocation());
        if (fallingLocation.getNearbyEntitiesByType(FallingBlock.class, 0.25).isEmpty())
            StringBlockState.spawnFalling(aboveBase, variation);
        handleFallingOraxenBlockAbove(aboveBase);
    }

    private boolean makePlayerPlaceBlock(final Player player, final EquipmentSlot hand, final ItemStack item,
            final Block placedAgainst, final BlockFace face, final StringBlockMechanic mechanic) {
        final Block target;
        final Material type = placedAgainst.getType();
        if (BlockHelpers.isReplaceable(type)) target = placedAgainst;
        else target = placedAgainst.getRelative(face);
        if (!Bukkit.isOwnedByCurrentRegion(target)) return false;
        if (!canWriteCarrier(target)) return false;
        if (!mechanic.canPlaceOn(face, placedAgainst)) return false;

        Block blockAbove = target.getRelative(BlockFace.UP);
        Range<Integer> worldHeightRange = Range.between(target.getWorld().getMinHeight(),
                target.getWorld().getMaxHeight() - 1);
        if (!worldHeightRange.contains(target.getY())) return false;
        if (mechanic.isTall() && (!worldHeightRange.contains(blockAbove.getY()) || !canWriteCarrier(blockAbove)))
            return false;
        if (!AntiGriefLib.canBuild(player, target.getLocation())) return false;
        if (mechanic.isTall() && !AntiGriefLib.canBuild(player, blockAbove.getLocation())) return false;

        // Capture the pre-placement states before staging the carrier for the synthetic events.
        final BlockState oldState = target.getState();
        final BlockState oldStateAbove = blockAbove.getState();
        target.setType(StringBlockState.MATERIAL, false);
        if (mechanic.isTall()) blockAbove.setType(StringBlockState.MATERIAL, false);

        final BlockPlaceEvent blockPlaceEvent = new BlockPlaceEvent(target, oldState, placedAgainst, item,
                player, true, hand);
        if (!blockPlaceEvent.callEvent() || !blockPlaceEvent.canBuild()) {
            oldState.update(true, false);
            if (mechanic.isTall()) oldStateAbove.update(true, false);
            return false;
        }

        int variation = mechanic.getCustomVariation();
        StringBlockState.mark(target, variation);
        if (mechanic.isTall()) StringBlockState.markUpper(blockAbove);

        OraxenStringBlockPlaceEvent oraxenPlaceEvent = new OraxenStringBlockPlaceEvent(mechanic, target, player,
                item, hand);
        if (!oraxenPlaceEvent.callEvent()) {
            StringBlockState.unmark(target);
            if (mechanic.isTall()) StringBlockState.unmark(blockAbove);
            oldState.update(true, false);
            if (mechanic.isTall()) oldStateAbove.update(true, false);
            return false;
        }

        if (!OraxenBlocks.placeStringBlock(target, mechanic, variation)) {
            StringBlockState.unmark(target);
            if (mechanic.isTall()) StringBlockState.unmark(blockAbove);
            oldState.update(true, false);
            if (mechanic.isTall()) oldStateAbove.update(true, false);
            return false;
        }
        if (mechanic.isStorage()) mechanic.getStorage().loadStorageContent(target, item);

        // Handle falling blocks
        if (mechanic.isFalling()) {
            Block below = target.getRelative(BlockFace.DOWN);
            if (below.getType() == Material.AIR || BlockHelpers.isReplaceable(below.getType())) {
                if (OraxenBlocks.remove(target.getLocation(), null)) {
                    Location fallingLocation = BlockHelpers.toCenterBlockLocation(target.getLocation());
                    if (fallingLocation.getNearbyEntitiesByType(FallingBlock.class, 0.25).isEmpty())
                        StringBlockState.spawnFalling(target, variation);
                    handleFallingOraxenBlockAbove(target);
                }
            }
        }

        if (player.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
        Utils.swingHand(player, hand);
        mechanic.runPlaceEvents(player);
        target.getWorld().sendGameEvent(player, GameEvent.BLOCK_PLACE, target.getLocation().toVector());
        return true;
    }

    private boolean canWriteCarrier(Block block) {
        if (block.getType() == Material.TRIPWIRE || StringBlockState.hasMarker(block)) return false;
        return BlockHelpers.isReplaceable(block.getType()) || block.getType() == StringBlockState.MATERIAL;
    }
}
