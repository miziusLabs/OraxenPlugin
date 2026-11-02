package io.th0rgal.oraxen.utils.breaker;

import io.th0rgal.oraxen.api.OraxenBlocks;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AdjacentNoteBlockUpdateHelper {

    private AdjacentNoteBlockUpdateHelper() {
    }

    public static boolean hasCustomVerticalNeighbor(final Block block) {
        return OraxenBlocks.isOraxenNoteBlock(block.getRelative(BlockFace.UP))
                || OraxenBlocks.isOraxenNoteBlock(block.getRelative(BlockFace.DOWN));
    }

    public static void resendCustomVerticalNeighbors(final Block changedBlock, final Player actor) {
        resendCustomVerticalNeighbors(changedBlock, actor, changedBlock.getBlockData());
    }

    public static void resendCustomVerticalNeighbors(final Block changedBlock, final Player actor,
                                                     final BlockData changedState) {
        final Map<Location, BlockData> updates = blockChangeStates(changedBlock, changedState);
        if (updates.isEmpty()) return;

        // Include the target in the same section update as its neighbors. Sending just the
        // neighbors can leave the client's predicted removal pending until a later packet.
        SchedulerUtil.runOnOwningThread(actor, () -> actor.sendMultiBlockChange(updates));

        final World world = changedBlock.getWorld();
        final Location origin = changedBlock.getLocation();
        final UUID actorId = actor.getUniqueId();
        if (!VersionUtil.isFoliaServer()) {
            for (final Player viewer : world.getPlayers()) {
                if (viewer.getUniqueId().equals(actorId) || !isWithinTrackingDistance(viewer, origin)) continue;
                viewer.sendMultiBlockChange(updates);
            }
            return;
        }

        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(actorId)) continue;
            SchedulerUtil.runForEntity(viewer, () -> {
                if (!viewer.isOnline() || !viewer.getWorld().equals(world)
                        || !isWithinTrackingDistance(viewer, origin)) return;
                viewer.sendMultiBlockChange(updates);
            }, null);
        }
    }

    static Map<Location, BlockData> blockChangeStates(final Block block, final BlockData changedState) {
        final Map<Location, BlockData> updates = customVerticalNeighborStates(block);
        if (!updates.isEmpty()) updates.put(block.getLocation(), changedState);
        return updates;
    }

    static Map<Location, BlockData> customVerticalNeighborStates(final Block block) {
        final Map<Location, BlockData> updates = new LinkedHashMap<>(2);
        for (final BlockFace face : new BlockFace[]{BlockFace.DOWN, BlockFace.UP}) {
            final Block neighbor = block.getRelative(face);
            if (OraxenBlocks.isOraxenNoteBlock(neighbor))
                updates.put(neighbor.getLocation(), neighbor.getBlockData());
        }
        return updates;
    }

    private static boolean isWithinTrackingDistance(final Player player, final Location origin) {
        final Location playerLocation = player.getLocation();
        final int trackingDistance = (Bukkit.getViewDistance() + 1) * 16;
        return Math.abs(playerLocation.getX() - origin.getX()) <= trackingDistance
                && Math.abs(playerLocation.getZ() - origin.getZ()) <= trackingDistance;
    }
}
