package io.th0rgal.oraxen.utils.breaker;

import io.th0rgal.oraxen.api.OraxenBlocks;
import io.th0rgal.oraxen.mechanics.provided.gameplay.chorusblock.ChorusBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.noteblock.NoteBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.shaped.ShapedBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockMechanic;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

public class CustomBlockMiningListener implements Listener {

    private static final NamespacedKey BREAK_SPEED_KEY = NamespacedKey.fromString("oraxen:custom_breaking_speed");
    // Used only when a carrier has no positive native hardness to scale against.
    private static final double VANILLA_BREAK_SPEED_BASE = 0.24D;
    private final BreakSpeedModifier breakSpeedModifier = new BreakSpeedModifier(BREAK_SPEED_KEY);

    /**
     * Returns true if the BLOCK_BREAK_SPEED attribute is available on this server version.
     * The attribute was added in MC 1.20.5 - on older versions, the BreakerSystem
     * timer-based approach is used instead.
     */
    public static boolean isSupported() {
        return BreakSpeedModifier.isSupported();
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageCustomBlock(final BlockDamageEvent event) {
        final Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        final Block block = event.getBlock();
        final ItemStack tool = player.getInventory().getItemInMainHand();
        final MiningProfile miningProfile = getMiningProfile(block, tool);
        if (miningProfile == null) {
            breakSpeedModifier.clear(player);
            return;
        }

        final double hardness = miningProfile.hardness();
        if (hardness <= 0.0D) {
            breakSpeedModifier.clear(player);
            event.setInstaBreak(true);
            return;
        }

        breakSpeedModifier.apply(player, breakSpeedMultiplier(miningProfile));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageAbort(final BlockDamageAbortEvent event) {
        breakSpeedModifier.clear(event.getPlayer());
    }

    @EventHandler
    public void onBlockBreak(final BlockBreakEvent event) {
        breakSpeedModifier.clear(event.getPlayer());
    }

    @EventHandler
    public void onDisconnect(final PlayerQuitEvent event) {
        breakSpeedModifier.clear(event.getPlayer());
    }

    @EventHandler
    public void onSwapHand(final PlayerSwapHandItemsEvent event) {
        breakSpeedModifier.clear(event.getPlayer());
    }

    @EventHandler
    public void onDropHand(final PlayerDropItemEvent event) {
        breakSpeedModifier.clear(event.getPlayer());
    }

    @Nullable
    private static MiningProfile getMiningProfile(final Block block, final ItemStack tool) {
        if (block.getType() == Material.NOTE_BLOCK) {
            NoteBlockMechanic mechanic = OraxenBlocks.getNoteBlockMechanic(block);
            if (mechanic == null) return null;
            if (mechanic.isDirectional() && !mechanic.getDirectional().isParentBlock()) {
                mechanic = mechanic.getDirectional().getParentMechanic();
                if (mechanic == null) return null;
            }
            return mechanic.hasHardness(tool) ? new MiningProfile(block, mechanic.getHardness(tool),
                    mechanic.getAttributeSpeedMultiplier(tool, block.getType())) : null;
        }

        if (block.getType() == Material.TRIPWIRE) {
            final StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(block);
            return mechanic != null && mechanic.hasHardness(tool) ? new MiningProfile(block, mechanic.getHardness(tool),
                    mechanic.getAttributeSpeedMultiplier(tool, block.getType())) : null;
        }

        if (block.getType() == Material.CHORUS_PLANT) {
            final ChorusBlockMechanic mechanic = OraxenBlocks.getChorusMechanic(block);
            return mechanic != null && mechanic.hasHardness(tool) ? new MiningProfile(block, mechanic.getHardness(tool),
                    mechanic.getAttributeSpeedMultiplier(tool, block.getType())) : null;
        }

        final ShapedBlockMechanic shapedMechanic = OraxenBlocks.getShapedMechanic(block);
        if (shapedMechanic != null) {
            if (shapedMechanic.hasHardness(tool)) return new MiningProfile(block, shapedMechanic.getHardness(tool),
                    shapedMechanic.getAttributeSpeedMultiplier(tool, block.getType()));
        }

        return null;
    }

    static boolean hasMiningProfile(final Block block, final ItemStack tool) {
        return getMiningProfile(block, tool) != null;
    }

    static double miningSpeedMultiplier(final double nativeHardness, final double customHardness,
                                        final double configuredToolSpeedMultiplier) {
        if (customHardness <= 0.0D) return 1.0D;
        final double hardnessScale = nativeHardness > 0.0D
                ? nativeHardness / customHardness
                : VANILLA_BREAK_SPEED_BASE / customHardness;
        return hardnessScale * configuredToolSpeedMultiplier;
    }

    private static double breakSpeedMultiplier(final MiningProfile miningProfile) {
        return miningSpeedMultiplier(miningProfile.block().getType().getHardness(), miningProfile.hardness(),
                miningProfile.speedMultiplier());
    }

    private record MiningProfile(Block block, double hardness, double speedMultiplier) {}

    /**
     * Calculates the progress the attribute-driven client would make in one tick without actually
     * applying the Oraxen modifier. Used when the server must own a note-block break to avoid the
     * client's predicted neighbour-state updates.
     */
    static float serverDrivenBreakProgress(final Player player, final Block block, final ItemStack tool) {
        final MiningProfile miningProfile = getMiningProfile(block, tool);
        if (miningProfile == null) return 0.0F;
        if (miningProfile.hardness() <= 0.0D) return 1.0F;

        // Block#getBreakSpeed already includes the carrier's hardness. Replace that hardness
        // with Oraxen's configured value, then apply the configured/native tool-speed ratio.
        final double progress = block.getBreakSpeed(player) * breakSpeedMultiplier(miningProfile);
        if (Double.isNaN(progress) || progress <= 0.0D) return 0.0F;
        return (float) Math.min(1.0D, progress);
    }
}
