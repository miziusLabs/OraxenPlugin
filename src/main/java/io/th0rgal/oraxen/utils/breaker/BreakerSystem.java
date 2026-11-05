package io.th0rgal.oraxen.utils.breaker;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.api.OraxenBlocks;
import io.th0rgal.oraxen.api.OraxenFurniture;
import io.th0rgal.oraxen.api.events.chorusblock.OraxenChorusBlockDamageEvent;
import io.th0rgal.oraxen.api.events.furniture.OraxenFurnitureDamageEvent;
import io.th0rgal.oraxen.api.events.noteblock.OraxenNoteBlockDamageEvent;
import io.th0rgal.oraxen.api.events.stringblock.OraxenStringBlockDamageEvent;
import io.th0rgal.oraxen.mechanics.provided.gameplay.block.BlockBreaking;
import io.th0rgal.oraxen.mechanics.provided.gameplay.block.BlockDurability;
import io.th0rgal.oraxen.mechanics.provided.gameplay.chorusblock.ChorusBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.furniture.FurnitureMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.noteblock.NoteBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.shaped.ShapedBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockMechanic;
import io.th0rgal.oraxen.nms.NMSHandlers;
import io.th0rgal.oraxen.utils.BlockHelpers;
import io.th0rgal.oraxen.utils.ItemUtils;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.VersionUtil;
import io.th0rgal.oraxen.utils.blocksounds.BlockSounds;
import io.th0rgal.oraxen.utils.drops.Drop;
import io.th0rgal.oraxen.utils.wrappers.EnchantmentWrapper;
import io.th0rgal.oraxen.protection.AntiGriefLib;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives custom block-breaking (custom hardness, furniture barriers, bedrock-break) from
 * Paper's {@link BlockDamageEvent}/{@link BlockDamageAbortEvent} and renders the break
 * animation through {@link Player#sendBlockDamage(Location, float, int)}. Both events fire
 * on the owning region thread, so this is Folia-safe without any packet-library dependency.
 */
public class BreakerSystem implements Listener {

    private static final AtomicInteger NEXT_BREAK_ANIMATION_ID = new AtomicInteger(Integer.MIN_VALUE);

    // Re-populated by mechanic factories on every reload while block-damage
    // handlers iterate it concurrently on region threads (Folia).
    public static final List<HardnessModifier> MODIFIERS = new CopyOnWriteArrayList<>();
    // Use thread-safe collections for Folia compatibility (concurrent region thread access)
    private final Map<Location, BreakSession> breakerSessions = new ConcurrentHashMap<>();
    private final Map<Location, SchedulerUtil.ScheduledTask> breakerPlaySound = new ConcurrentHashMap<>();
    private final Map<BreakSession, BreakSuppression> clientSideBreakSuppressions = new ConcurrentHashMap<>();
    private final ThreadLocal<BlockBreakEvent> syntheticBreak = new ThreadLocal<>();

    private static final class BreakSession {
        private final Player owner;
        private final java.util.UUID ownerId;
        private final int animationId = NEXT_BREAK_ANIMATION_ID.getAndIncrement();
        private final AtomicReference<BreakSessionState> state = new AtomicReference<>(BreakSessionState.ACTIVE);
        private volatile SchedulerUtil.ScheduledTask task;

        private BreakSession(final Player owner) {
            this.owner = owner;
            this.ownerId = owner.getUniqueId();
        }
    }

    private enum BreakSessionState {
        ACTIVE,
        COMMITTING,
        CANCELLED,
        FINISHED
    }

    private record BreakSuppression(BreakSession session, Player player, Location location) {
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockDamage(final BlockDamageEvent event) {
        final BlockFace blockFace;
        try {
            blockFace = event.getBlockFace();
        } catch (final IllegalStateException ignored) {
            // Synthetic damage events created with the legacy constructor have no clicked face.
            // They do not represent a new client mining action for the breaker to own.
            return;
        }
        handleEvent(event.getPlayer(), event.getBlock(), blockFace, () -> event.setCancelled(true), true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAdjacentVanillaBlockDamage(final BlockDamageEvent event) {
        if (!shouldProtectAdjacentVanillaBlock(event.getPlayer(), event.getBlock())) return;
        handleAdjacentVanillaBlock(event.getPlayer(), event.getBlock(), () -> event.setCancelled(true),
                event.getInstaBreak());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdjacentBlockBreak(final BlockBreakEvent event) {
        final Block block = event.getBlock();
        // Our timers publish a break event before their final permission check and removal.
        // They send the correction themselves after the block has actually been removed.
        if (syntheticBreak.get() == event) return;
        if (!hasCustomVerticalNoteBlockNeighbor(block)) return;

        // Creative and instant breaks can bypass BlockDamageEvent. Repair their predicted
        // neighbor states too, before the normal dig acknowledgement settles the prediction.
        AdjacentNoteBlockUpdateHelper.resendCustomVerticalNeighbors(block, event.getPlayer(),
                Material.AIR.createBlockData());
        final Player player = event.getPlayer();
        final Location location = block.getLocation();
        SchedulerUtil.runOnOwningThread(player, () ->
                NMSHandlers.getHandler().acknowledgeBlockChanges(player, location, false));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockDamageAbort(final BlockDamageAbortEvent event) {
        final Block block = event.getBlock();
        final Location location = block.getLocation();
        final BreakSession session = breakerSessions.get(location);
        final boolean ownedByAnotherPlayer = session != null
                && !session.ownerId.equals(event.getPlayer().getUniqueId());
        if (ownedByAnotherPlayer) {
            clearPlayerBlockBreakAnimation(event.getPlayer(), location);
            return;
        }
        final boolean resetAnimation = breakerSessions.containsKey(location)
                || OraxenBlocks.isOraxenBlock(block)
                || OraxenFurniture.getFurnitureMechanic(block) != null
                || hasCustomVerticalNoteBlockNeighbor(block);
        handleEvent(event.getPlayer(), block, BlockFace.UP, () -> {
        }, false);
        if (resetAnimation) {
            if (session == null) {
                resetBlockBreakAnimations(block.getWorld(), Collections.singletonList(location));
                clearPlayerBlockBreakAnimation(event.getPlayer(), location);
            } else clearBreakSessionAnimations(block.getWorld(), location, session);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        final Player player = event.getPlayer();
        for (final Map.Entry<Location, BreakSession> entry : breakerSessions.entrySet()) {
            final Location location = entry.getKey();
            final BreakSession session = entry.getValue();
            if (!session.ownerId.equals(player.getUniqueId()) || !stopBlockBreaker(location, session)) continue;

            stopBlockHitSound(location);
            clearBreakSessionAnimations(location.getWorld(), location, session);
        }
    }

    private void sendBlockBreak(final Player player, final Location location, final int stage) {
        sendBlockBreak(player, location, stage, location.hashCode());
    }

    private void sendBlockBreak(final Player player, final Location location, final int stage, final int sourceId) {
        // Paper maps progress 0.0 to the "clear animation" stage; (stage + 1) / 10 maps each
        // crack stage 0-9 back to itself ((int) (9 * progress)) without ever colliding with 0.
        final float progress = stage < 0 || stage > 9 ? 0f : (stage + 1) / 10f;
        player.sendBlockDamage(location, progress, sourceId);
    }

    private void clearPlayerBlockBreakAnimation(final Player player, final Location location) {
        final int sourceId = player.getEntityId();
        SchedulerUtil.runOnOwningThread(player,
                () -> sendBlockBreak(player, location, -1, sourceId));
        sendBlockBreakToViewers(location.getWorld(), location, Collections.singletonList(location), -1, sourceId);
    }

    private void handleEvent(Player player, Block block, BlockFace blockFace, Runnable cancel, boolean startedDigging) {
        final Location location = block.getLocation();
        final World world = block.getWorld();

        // Always release client-side suppression on abort, even if the block was replaced or the
        // player changed game mode since START_DESTROY_BLOCK and no modifier matches any more.
        if (!startedDigging) {
            final BreakSession session = breakerSessions.get(location);
            if (session != null && session.ownerId.equals(player.getUniqueId())
                    && stopBlockBreaker(location, session))
                stopBlockHitSound(location);
        } else {
            stopOtherClientSideBreakers(player, location);
            final BreakSession previousSession = breakerSessions.get(location);
            if (previousSession != null && stopBlockBreaker(location, previousSession)) {
                stopBlockHitSound(location);
                clearBreakSessionAnimations(world, location, previousSession);
            }
            if (ClientSideBlockBreakSuppressor.isSupported())
                ClientSideBlockBreakSuppressor.restore(player);
        }

        if (player.getGameMode() == GameMode.CREATIVE) return;

        final ItemStack item = player.getInventory().getItemInMainHand();
        HardnessModifier triggeredModifier = null;
        for (final HardnessModifier modifier : MODIFIERS) {
            if (modifier.isTriggered(player, block, item)) {
                triggeredModifier = modifier;
                break;
            }
        }

        if (triggeredModifier == null) return;
        final long period = triggeredModifier.getPeriod(player, block, item);
        if (period == 0) return;

        NoteBlockMechanic noteMechanic = OraxenBlocks.getNoteBlockMechanic(block);
        StringBlockMechanic stringMechanic = OraxenBlocks.getStringMechanic(block);
        ChorusBlockMechanic chorusMechanic = OraxenBlocks.getChorusMechanic(block);
        ShapedBlockMechanic shapedMechanic = OraxenBlocks.getShapedMechanic(block);
        FurnitureMechanic furnitureMechanic = OraxenFurniture.getFurnitureMechanic(block);
        if (block.getType() == Material.NOTE_BLOCK && noteMechanic == null) return;
        if (block.getType() == Material.TRIPWIRE && stringMechanic == null) return;
        if (block.getType() == Material.BARRIER && furnitureMechanic == null) return;

        // A vanilla client starts destroy progress before BlockDamageEvent reaches the server.
        // Applying a custom speed modifier here leaves the first client progress sample at vanilla
        // speed, so own configured custom-block progress on the server instead. Note-block stacks
        // also need this path to prevent predicted neighbor-state updates from changing their models.
        final boolean protectNoteBlockStack = noteMechanic != null
                && ClientSideBlockBreakSuppressor.isSupported()
                && hasCustomVerticalNoteBlockNeighbor(block);
        final boolean canUseAttributeTiming = ClientSideBlockBreakSuppressor.isSupported()
                && (protectNoteBlockStack || (!VersionUtil.isFoliaServer()
                && CustomBlockMiningListener.hasMiningProfile(block, item)));
        if (CustomBlockMiningListener.isSupported()
                && (noteMechanic != null || stringMechanic != null || shapedMechanic != null
                || chorusMechanic != null) && !canUseAttributeTiming) {
            return;
        }

        if (startedDigging) {
            // Get these when block is started being broken to minimize checks & allow for proper damage checks later
            final Drop drop;
            final BlockBreaking.DurabilityAction durabilityAction;
            if (furnitureMechanic != null) {
                drop = furnitureMechanic.getDrop() != null ? furnitureMechanic.getDrop() : Drop.emptyDrop();
                durabilityAction = null;
            } else if (noteMechanic != null) {
                drop = noteMechanic.getDrop(item) != null ? noteMechanic.getDrop(item) : Drop.emptyDrop();
                durabilityAction = noteMechanic.getDurabilityAction(item);
            } else if (stringMechanic != null) {
                drop = stringMechanic.getDrop(item) != null ? stringMechanic.getDrop(item) : Drop.emptyDrop();
                durabilityAction = stringMechanic.getDurabilityAction(item);
            } else if (chorusMechanic != null) {
                drop = chorusMechanic.getDrop(item) != null ? chorusMechanic.getDrop(item) : Drop.emptyDrop();
                durabilityAction = chorusMechanic.getDurabilityAction(item);
            } else if (shapedMechanic != null) {
                drop = shapedMechanic.getDrop(item) != null ? shapedMechanic.getDrop(item) : Drop.emptyDrop();
                durabilityAction = shapedMechanic.getDurabilityAction(item);
            } else {
                drop = null;
                durabilityAction = null;
            }

            final BreakSession session = new BreakSession(player);
            cancel.run();
            if (!registerBreakerSession(location, session)) return;

            // Defer the rest to the next tick so the PlayerInteractEvent and Oraxen damage
            // events fire after BlockDamageEvent processing has fully completed.
            final HardnessModifier modifier = triggeredModifier;
            final boolean useAttributeTiming = canUseAttributeTiming;
            final float serverBreakProgress = useAttributeTiming
                    ? CustomBlockMiningListener.serverDrivenBreakProgress(player, block, item)
                    : 0.0F;
            // Capture the normal attribute-driven progress before setting the player's break speed
            // to zero for client prediction suppression.
            if (useAttributeTiming) {
                suppressClientSideBreaking(player, location, session);
                clearPlayerBlockBreakAnimation(player, location);
                final int initialStage = serverBreakProgress <= 0.0F || serverBreakProgress >= 1.0F
                        ? -1 : 0;
                SchedulerUtil.runOnOwningThread(player, () -> {
                    if (isCurrentBreaker(location, session))
                        sendBlockBreak(player, location, initialStage, session.animationId);
                });
            }
            SchedulerUtil.runAtLocation(location, () -> {
                if (!isCurrentBreaker(location, session)) return;

                // Fire PlayerInteractEvent for plugin support (cancellation state is ignored).
                // Oraxen click actions already ran on the real interact and skip this copy.
                final PlayerInteractEvent playerInteractEvent =
                    new PlayerInteractEvent(player, Action.LEFT_CLICK_BLOCK, item, block, blockFace, EquipmentSlot.HAND);
                SyntheticBlockInteract.call(playerInteractEvent);

                // If the relevant damage event is cancelled, stop the breaker
                if (blockDamageEventCancelled(block, player)) {
                    stopBlockBreaker(location, session);
                    resetBlockBreakAnimations(world, Collections.singletonList(location), session.animationId);
                    clearPlayerBlockBreakAnimation(player, location);
                    return;
                }

                // Methods for sending multi-barrier block-breaks
                final List<Location> furnitureBarrierLocations = furnitureBarrierLocations(furnitureMechanic, block);
                startBlockHitSound(location);

                // Vanilla per-tick dig progress the client makes on this block (1.0 = finished).
                // Unbreakable blocks (furniture barriers, bedrock) yield 0, so the guard below
                // never triggers for them.
                final float clientBreakSpeed = useAttributeTiming ? 0.0F : block.getBreakSpeed(player);

                final int[] valueHolder = {0};
                final float[] clientProgress = {0f};
                final float[] serverProgress = {0.0F};
                final boolean[] breakerFinished = {false};
                final long timerPeriod = useAttributeTiming ? 1L : period;
                final Runnable advanceBreaker = () -> {
                    if (!isCurrentBreaker(location, session)) {
                        breakerFinished[0] = true;
                        return;
                    }
                    if (breakerFinished[0]) return;

                    if (!useAttributeTiming && item.getEnchantmentLevel(EnchantmentWrapper.EFFICIENCY) >= 5)
                        valueHolder[0] = 10;

                    // Replaces the old STOP_DESTROY_BLOCK packet handling: once the client's own
                    // (vanilla-speed) dig completes, it stops digging without ever sending the abort
                    // that fires BlockDamageAbortEvent, so this timer would otherwise keep running
                    // and break the block by itself after the player let go. Stop once the client
                    // must have finished (or quit); if the player is still holding the button, the
                    // client re-starts digging and the new BlockDamageEvent restarts this breaker.
                    clientProgress[0] += clientBreakSpeed * timerPeriod;
                    if ((!useAttributeTiming && !player.isOnline())
                            || (!useAttributeTiming && valueHolder[0] < 10 && clientProgress[0] >= 1f)) {
                        stopBlockBreaker(location, session);
                        stopBlockHitSound(location);
                        clearBreakSessionAnimations(world, location, session);
                        breakerFinished[0] = true;
                        return;
                    }

                    if (useAttributeTiming) {
                        if (serverProgress[0] < 1.0F)
                            serverProgress[0] = Math.min(1.0F, serverProgress[0] + serverBreakProgress);
                        valueHolder[0] = Math.min(10, (int) (serverProgress[0] * 10.0F));
                        if (serverProgress[0] > 0.0F) {
                            // Match vanilla's ten crack stages. Stage 9 covers the final tenth of
                            // progress and is sent on the same tick that reaches full progress.
                            sendBlockBreakToViewers(world, location, Collections.singletonList(location),
                                    Math.min(9, (int) (serverProgress[0] * 10.0F)), session.animationId);
                        }
                    } else {
                        sendBlockBreakToViewers(world, location,
                                furnitureMechanic != null ? furnitureBarrierLocations : Collections.singletonList(location),
                                valueHolder[0], session.animationId);
                        if (valueHolder[0]++ < 9) return;
                    }

                    if (useAttributeTiming && valueHolder[0] < 10) return;

                    if (!session.state.compareAndSet(BreakSessionState.ACTIVE, BreakSessionState.COMMITTING)) {
                        breakerFinished[0] = true;
                        return;
                    }

                    try {
                        BlockDurability.setSuppressVanillaDamageCancellation(true);
                        final BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
                        syntheticBreak.set(breakEvent);
                        final boolean canBreak;
                        try {
                            canBreak = breakEvent.callEvent() && AntiGriefLib.canBreak(player, location);
                        } finally {
                            syntheticBreak.remove();
                            BlockDurability.setSuppressVanillaDamageCancellation(false);
                        }
                        if (canBreak) {
                            // Damage item with properties identified earlier, unless the block mechanic configured durability itself.
                            if (durabilityAction == null) ItemUtils.damageItem(player, drop, item);
                            modifier.breakBlock(player, block, item);
                            AdjacentNoteBlockUpdateHelper.resendCustomVerticalNeighbors(block, player);
                        }
                    } finally {
                        BlockDurability.setSuppressVanillaDamageCancellation(false);
                        final boolean removed = finishBlockBreaker(location, session);
                        if (removed) stopBlockHitSound(location);
                        sendBlockBreakToViewers(world, location,
                                furnitureMechanic != null ? furnitureBarrierLocations : Collections.singletonList(location),
                                valueHolder[0], session.animationId);
                        clearPlayerBlockBreakAnimation(player, location);
                        breakerFinished[0] = true;
                    }
                };

                // START_DESTROY_BLOCK does not report elapsed client progress. Start the server
                // accumulator at zero and apply one sample on the first owning-region tick after
                // damage listeners have run. The initial stage above only starts the crack effect.
                if (useAttributeTiming) advanceBreaker.run();
                if (breakerFinished[0]) return;

                SchedulerUtil.ScheduledTask breakerTask = SchedulerUtil.runAtLocationTimer(
                        location, 1L, timerPeriod, advanceBreaker);
                session.task = breakerTask;
                if (!isCurrentBreaker(location, session)) breakerTask.cancel();
            });
        } else {
            cancel.run();
            // Use entity scheduler for player operations on Folia (player may move to different region)
            SchedulerUtil.runForEntity(player, () -> {
                if (!AntiGriefLib.canBreak(player, location))
                    player.sendBlockChange(location, block.getBlockData());
            });

            resetBlockBreakAnimations(world, Collections.singletonList(location));
        }
    }

    private void resetBlockBreakAnimations(final World world, final List<Location> locations) {
        resetBlockBreakAnimations(world, locations, null);
    }

    private void resetBlockBreakAnimations(final World world, final List<Location> locations,
                                           final Integer sourceId) {
        if (locations.isEmpty()) return;

        for (Location resetLocation : locations)
            SchedulerUtil.runAtLocation(resetLocation, () ->
                    sendBlockBreakToViewers(world, resetLocation, Collections.singletonList(resetLocation), 10, sourceId));
    }

    private void clearBreakSessionAnimations(final World world, final Location location,
                                             final BreakSession session) {
        resetBlockBreakAnimations(world, Collections.singletonList(location), session.animationId);
        clearPlayerBlockBreakAnimation(session.owner, location);
    }

    private void sendBlockBreakToViewers(World world, Location origin, List<Location> breakLocations, int stage) {
        sendBlockBreakToViewers(world, origin, breakLocations, stage, null);
    }

    private void sendBlockBreakToViewers(World world, Location origin, List<Location> breakLocations,
                                         int stage, Integer sourceId) {
        if (breakLocations.isEmpty()) return;

        if (!VersionUtil.isFoliaServer()) {
            for (final Entity entity : world.getNearbyEntities(origin, 16, 16, 16)) {
                if (entity instanceof Player viewer) {
                    for (Location breakLocation : breakLocations)
                        sendBlockBreak(viewer, breakLocation, stage,
                                sourceId == null ? breakLocation.hashCode() : sourceId);
                }
            }
            return;
        }

        // Folia does not allow arbitrary nearby-entity scans from a region thread.
        // Hop to each player's entity scheduler before reading their location/world.
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            SchedulerUtil.runForEntity(viewer, () -> {
                if (!viewer.isOnline() || !viewer.getWorld().equals(world)) return;
                if (viewer.getLocation().distanceSquared(origin) > 16 * 16) return;
                for (Location breakLocation : breakLocations)
                    sendBlockBreak(viewer, breakLocation, stage,
                            sourceId == null ? breakLocation.hashCode() : sourceId);
            }, null);
        }
    }

    private List<Location> furnitureBarrierLocations(FurnitureMechanic furnitureMechanic, Block block) {
        // Get base entity directly - we're already on the correct thread context
        // (main thread for Bukkit, region thread for Folia) from block damage events.
        if (furnitureMechanic == null) return Collections.singletonList(block.getLocation());

        Entity furnitureBaseEntity = furnitureMechanic.getBaseEntity(block);
        if (furnitureBaseEntity == null) return Collections.singletonList(block.getLocation());

        return furnitureMechanic.getLocations(
                FurnitureMechanic.getFurnitureYaw(furnitureBaseEntity),
                furnitureBaseEntity.getLocation(),
                furnitureMechanic.getBarriers());
    }

    private boolean blockDamageEventCancelled(Block block, Player player) {
        if (!breakerSessions.containsKey(block.getLocation())) return false;
        if (OraxenBlocks.getShapedMechanic(block) != null) return false;

        // Events must be dispatched synchronously to check cancellation status.
        // This is called from a scheduled task on the main/region thread.
        switch (block.getType()) {
            case NOTE_BLOCK -> {
                NoteBlockMechanic mechanic = OraxenBlocks.getNoteBlockMechanic(block);
                if (mechanic == null) return true;
                OraxenNoteBlockDamageEvent event = new OraxenNoteBlockDamageEvent(mechanic, block, player);
                event.callEvent();
                return event.isCancelled();
            }
            case TRIPWIRE -> {
                StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(block);
                if (mechanic == null) return true;
                OraxenStringBlockDamageEvent event = new OraxenStringBlockDamageEvent(mechanic, block, player);
                event.callEvent();
                return event.isCancelled();
            }
            case CHORUS_PLANT -> {
                ChorusBlockMechanic mechanic = OraxenBlocks.getChorusMechanic(block);
                if (mechanic == null) return true;
                OraxenChorusBlockDamageEvent event = new OraxenChorusBlockDamageEvent(mechanic, block, player);
                event.callEvent();
                return event.isCancelled();
            }
            case BARRIER -> {
                FurnitureMechanic mechanic = OraxenFurniture.getFurnitureMechanic(block);
                if (mechanic == null) return true;
                Entity baseEntity = mechanic.getBaseEntity(block);
                if (baseEntity == null) return true;
                OraxenFurnitureDamageEvent event = new OraxenFurnitureDamageEvent(mechanic, baseEntity, player, block);
                event.callEvent();
                return event.isCancelled();
            }
            case BEDROCK -> { // For BedrockBreakMechanic
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    private boolean stopBlockBreaker(final Location location) {
        return stopBlockBreaker(location, null);
    }

    private boolean stopBlockBreaker(final Location location, final BreakSession expectedSession) {
        final BreakSession session = breakerSessions.get(location);
        if (session != null) {
            if (expectedSession != null && session != expectedSession) return false;
            if (!session.state.compareAndSet(BreakSessionState.ACTIVE, BreakSessionState.CANCELLED)) return false;
            if (session.task != null) session.task.cancel();
            breakerSessions.remove(location, session);
            restoreClientSideBreaking(location, session);
            return true;
        } else {
            if (expectedSession != null) return false;
        }
        return false;
    }

    private boolean registerBreakerSession(final Location location, final BreakSession session) {
        while (true) {
            final BreakSession previous = breakerSessions.get(location);
            if (previous != null) {
                final BreakSessionState previousState = previous.state.get();
                if (previousState == BreakSessionState.COMMITTING) return false;
                if (previousState == BreakSessionState.ACTIVE
                        && !previous.state.compareAndSet(BreakSessionState.ACTIVE, BreakSessionState.CANCELLED))
                    continue;

                if (previous.task != null) previous.task.cancel();
                if (breakerSessions.remove(location, previous)) {
                    restoreClientSideBreaking(location, previous);
                    clearBreakSessionAnimations(location.getWorld(), location, previous);
                }
                continue;
            }
            if (breakerSessions.putIfAbsent(location, session) == null) return true;
        }
    }

    private boolean finishBlockBreaker(final Location location, final BreakSession session) {
        session.state.set(BreakSessionState.FINISHED);
        if (session.task != null) session.task.cancel();
        final boolean removed = breakerSessions.remove(location, session);
        restoreClientSideBreaking(location, session);
        return removed;
    }

    private boolean isCurrentBreaker(final Location location, final BreakSession session) {
        return session.state.get() == BreakSessionState.ACTIVE
                && breakerSessions.get(location) == session;
    }

    private void stopOtherClientSideBreakers(final Player player, final Location target) {
        for (final BreakSuppression suppression : clientSideBreakSuppressions.values()) {
            final Location activeLocation = suppression.location();
            if (suppression.player() != player || activeLocation.equals(target)) continue;

            if (!stopBlockBreaker(activeLocation, suppression.session())) continue;
            stopBlockHitSound(activeLocation);
            clearBreakSessionAnimations(activeLocation.getWorld(), activeLocation, suppression.session());
        }
    }

    static boolean hasCustomVerticalNoteBlockNeighbor(final Block block) {
        return AdjacentNoteBlockUpdateHelper.hasCustomVerticalNeighbor(block);
    }

    static boolean shouldProtectAdjacentVanillaBlock(final Player player, final Block block) {
        return ClientSideBlockBreakSuppressor.isSupported()
                && !OraxenBlocks.isOraxenBlock(block)
                && OraxenFurniture.getFurnitureMechanic(block) == null
                && hasCustomVerticalNoteBlockNeighbor(block);
    }

    private void handleAdjacentVanillaBlock(final Player player, final Block block, final Runnable cancel,
                                            final boolean instantBreak) {
        // The target update changes a vertical note block's rendered state on the client even
        // though its authoritative server state is unchanged. Own the break until completion,
        // then send that unchanged state directly after the target's removal packet.
        final Location location = block.getLocation();
        final World world = block.getWorld();
        final BlockData originalData = block.getBlockData();
        final float progressPerTick = instantBreak || player.getGameMode() == GameMode.CREATIVE
                ? 1.0F
                : Math.max(0.0F, block.getBreakSpeed(player));

        final BreakSession previousSession = breakerSessions.get(location);
        if (previousSession != null && stopBlockBreaker(location, previousSession)) {
            stopBlockHitSound(location);
            clearBreakSessionAnimations(world, location, previousSession);
        }
        final BreakSession session = new BreakSession(player);
        cancel.run();
        if (!registerBreakerSession(location, session)) return;
        suppressClientSideBreaking(player, location, session);
        clearPlayerBlockBreakAnimation(player, location);

        AdjacentNoteBlockUpdateHelper.resendCustomVerticalNeighbors(block, player, originalData);
        SchedulerUtil.runOnOwningThread(player, () ->
                NMSHandlers.getHandler().acknowledgeBlockChanges(player, location, false));

        final float[] progress = {0.0F};
        final SchedulerUtil.ScheduledTask task = SchedulerUtil.runAtLocationTimer(location, 1L, 1L, () -> {
            if (!isCurrentBreaker(location, session)) return;
            if (!block.getBlockData().equals(originalData)) {
                    if (stopBlockBreaker(location, session))
                        clearBreakSessionAnimations(world, location, session);
                    return;
            }

            progress[0] += progressPerTick;
            if (progress[0] < 1.0F) {
                final int stage = Math.min(8, (int) (progress[0] * 9.0F));
                sendBlockBreakToViewers(world, location, Collections.singletonList(location), stage, session.animationId);
                return;
            }

            if (!session.state.compareAndSet(BreakSessionState.ACTIVE, BreakSessionState.COMMITTING)) return;
            try {
                if (AntiGriefLib.canBreak(player, location) && player.breakBlock(block))
                    AdjacentNoteBlockUpdateHelper.resendCustomVerticalNeighbors(block, player);
            } finally {
                finishBlockBreaker(location, session);
                clearBreakSessionAnimations(world, location, session);
            }
        });
        session.task = task;
        if (!isCurrentBreaker(location, session)) task.cancel();
    }

    private void suppressClientSideBreaking(final Player player, final Location location,
                                            final BreakSession session) {
        if (!isCurrentBreaker(location, session)) return;
        final BreakSuppression suppression = new BreakSuppression(session, player, location);
        clientSideBreakSuppressions.put(session, suppression);
        if (!isCurrentBreaker(location, session)) {
            restoreClientSideBreaking(location, session);
            return;
        }
        SchedulerUtil.runOnOwningThread(player, () -> {
            if (clientSideBreakSuppressions.get(session) == suppression && isCurrentBreaker(location, session))
                ClientSideBlockBreakSuppressor.suppress(player);
        });
    }

    private void restoreClientSideBreaking(final Location location, final BreakSession session) {
        final BreakSuppression suppression = clientSideBreakSuppressions.get(session);
        if (suppression == null || !suppression.location().equals(location)
                || !clientSideBreakSuppressions.remove(session, suppression)) return;
        final Player player = suppression.player();
        if (clientSideBreakSuppressions.values().stream().anyMatch(active -> active.player() == player)) return;

        // A new break can begin before this entity task runs. In that case the newer break still
        // owns the suppression and will restore the real effects when it stops.
        final Runnable restore = () -> {
            if (clientSideBreakSuppressions.values().stream().anyMatch(active -> active.player() == player)) return;
            ClientSideBlockBreakSuppressor.restore(player);
        };
        try {
            SchedulerUtil.runOnOwningThread(player, restore);
        } catch (final RuntimeException ignored) {
            // The scheduler can already be unavailable while the plugin/server is disabling.
            restore.run();
        }
    }

    private void startBlockHitSound(Location location) {
        BlockSounds blockSounds = getBlockSounds(location.getBlock());

        if (!breakerSessions.containsKey(location) || blockSounds == null || !blockSounds.hasHitSound()) {
            stopBlockHitSound(location);
            return;
        }

        stopBlockHitSound(location);
        breakerPlaySound.put(location, SchedulerUtil.runAtLocationTimer(location, 0L, 4L,
                () -> BlockHelpers.playCustomBlockSound(location, getHitSound(location.getBlock()), blockSounds.getHitVolume(), blockSounds.getHitPitch())));
    }

    private void stopBlockHitSound(Location location) {
        Optional.ofNullable(breakerPlaySound.remove(location)).ifPresent(SchedulerUtil.ScheduledTask::cancel);
    }

    private BlockSounds getBlockSounds(Block block) {
        ConfigurationSection soundSection = OraxenPlugin.get().getConfigsManager().getMechanics().getConfigurationSection("custom_block_sounds");
        if (soundSection == null) return null;
        switch (block.getType()) {
            case NOTE_BLOCK -> {
                NoteBlockMechanic mechanic = OraxenBlocks.getNoteBlockMechanic(block);
                if (mechanic == null || !mechanic.hasBlockSounds()) return null;
                if (!BlockSounds.isBlockSoundEnabled(soundSection)) return null;
                else return mechanic.getBlockSounds();
            }
            case TRIPWIRE -> {
                StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(block);
                if (mechanic == null || !mechanic.hasBlockSounds()) return null;
                if (!BlockSounds.isStringBlockSoundEnabled(soundSection)) return null;
                else return mechanic.getBlockSounds();
            }
            case BARRIER -> {
                FurnitureMechanic mechanic = OraxenFurniture.getFurnitureMechanic(block);
                if (mechanic == null || !mechanic.hasBlockSounds()) return null;
                if (!BlockSounds.isFurnitureSoundEnabled(soundSection)) return null;
                else return mechanic.getBlockSounds();
            }
            default -> {
                ShapedBlockMechanic mechanic = OraxenBlocks.getShapedMechanic(block);
                return mechanic != null && mechanic.hasBlockSounds() && BlockSounds.isBlockSoundEnabled(soundSection)
                        ? mechanic.getBlockSounds()
                        : null;
            }
        }
    }

    private String getHitSound(Block block) {
        ConfigurationSection soundSection = OraxenPlugin.get().getConfigsManager().getMechanics().getConfigurationSection("custom_block_sounds");
        if (soundSection == null) return null;
        BlockSounds sounds = getBlockSounds(block);
        if (sounds == null) return null;
        return switch (block.getType()) {
            case NOTE_BLOCK -> sounds.hasHitSound() ? sounds.getHitSound() : "required.wood.hit";
            case TRIPWIRE -> sounds.hasHitSound() ? sounds.getHitSound() : "block.tripwire.detach";
            case BARRIER -> sounds.hasHitSound() ? sounds.getHitSound() : "required.stone.hit";
            default -> sounds.hasHitSound() ? sounds.getHitSound() : block.getBlockData().getSoundGroup().getHitSound().getKey().toString();
        };
    }
}
