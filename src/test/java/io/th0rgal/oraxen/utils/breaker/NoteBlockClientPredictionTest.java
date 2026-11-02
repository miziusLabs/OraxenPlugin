package io.th0rgal.oraxen.utils.breaker;

import io.th0rgal.oraxen.api.OraxenBlocks;
import io.th0rgal.oraxen.api.OraxenFurniture;
import io.th0rgal.oraxen.nms.NMSHandler;
import io.th0rgal.oraxen.nms.NMSHandlers;
import io.th0rgal.oraxen.utils.PotionUtils;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class NoteBlockClientPredictionTest {

    @Test
    void breakWithoutDamageEventCorrectsNeighborsBeforeAcknowledgingPrediction() {
        final Block block = mock(Block.class);
        final Player player = mock(Player.class);
        final Location location = new Location(null, 0, 15, 0);
        final BlockData air = mock(BlockData.class);
        final NMSHandler handler = mock(NMSHandler.class);
        when(block.getLocation()).thenReturn(location);

        try (MockedStatic<AdjacentNoteBlockUpdateHelper> updates = mockStatic(AdjacentNoteBlockUpdateHelper.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<SchedulerUtil> scheduler = mockStatic(SchedulerUtil.class);
             MockedStatic<NMSHandlers> nms = mockStatic(NMSHandlers.class)) {
            updates.when(() -> AdjacentNoteBlockUpdateHelper.hasCustomVerticalNeighbor(block)).thenReturn(true);
            bukkit.when(() -> Bukkit.createBlockData(Material.AIR)).thenReturn(air);
            nms.when(NMSHandlers::getHandler).thenReturn(handler);
            scheduler.when(() -> SchedulerUtil.runOnOwningThread(org.mockito.ArgumentMatchers.eq(player),
                    org.mockito.ArgumentMatchers.any(Runnable.class))).thenAnswer(invocation -> {
                        invocation.<Runnable>getArgument(1).run();
                        return null;
                    });
            updates.when(() -> AdjacentNoteBlockUpdateHelper.resendCustomVerticalNeighbors(block, player, air))
                    .thenAnswer(invocation -> {
                        verify(handler, org.mockito.Mockito.never())
                                .acknowledgeBlockChanges(player, location, false);
                        return null;
                    });

            new BreakerSystem().onAdjacentBlockBreak(new BlockBreakEvent(block, player));

            updates.verify(() -> AdjacentNoteBlockUpdateHelper.resendCustomVerticalNeighbors(block, player, air));
            verify(handler).acknowledgeBlockChanges(player, location, false);
        }
    }

    @Test
    void ignoresSyntheticBlockDamageWithoutABlockFace() {
        final BlockDamageEvent event = mock(BlockDamageEvent.class);
        when(event.getBlockFace()).thenThrow(new IllegalStateException("BlockFace is not available for this event"));

        assertDoesNotThrow(() -> new BreakerSystem().onBlockDamage(event));

        verify(event).getBlockFace();
        verifyNoMoreInteractions(event);
    }

    @Test
    void fullBlockBetweenCustomNoteBlocksRequiresServerAuthoritativeBreaking() {
        final Block block = mock(Block.class);
        final Block blockAbove = mock(Block.class);
        final Block blockBelow = mock(Block.class);
        when(block.getRelative(BlockFace.UP)).thenReturn(blockAbove);
        when(block.getRelative(BlockFace.DOWN)).thenReturn(blockBelow);

        try (MockedStatic<OraxenBlocks> blocks = mockStatic(OraxenBlocks.class)) {
            blocks.when(() -> OraxenBlocks.isOraxenNoteBlock(blockAbove)).thenReturn(true);
            blocks.when(() -> OraxenBlocks.isOraxenNoteBlock(blockBelow)).thenReturn(true);

            assertTrue(BreakerSystem.hasCustomVerticalNoteBlockNeighbor(block));
        }
    }

    @Test
    void protectsVanillaBlocksBetweenNoteBlocksEvenWhenAHardnessModifierMatches() {
        final Block block = mock(Block.class);
        final Block blockAbove = mock(Block.class);
        final Block blockBelow = mock(Block.class);
        final Player player = mock(Player.class);
        when(block.getRelative(BlockFace.UP)).thenReturn(blockAbove);
        when(block.getRelative(BlockFace.DOWN)).thenReturn(blockBelow);

        final HardnessModifier modifier = mock(HardnessModifier.class);
        when(modifier.isTriggered(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        BreakerSystem.MODIFIERS.add(modifier);
        try (MockedStatic<OraxenBlocks> blocks = mockStatic(OraxenBlocks.class);
             MockedStatic<OraxenFurniture> furniture = mockStatic(OraxenFurniture.class)) {
            blocks.when(() -> OraxenBlocks.isOraxenNoteBlock(blockAbove)).thenReturn(true);
            blocks.when(() -> OraxenBlocks.isOraxenNoteBlock(blockBelow)).thenReturn(true);
            blocks.when(() -> OraxenBlocks.isOraxenBlock(block)).thenReturn(false);
            furniture.when(() -> OraxenFurniture.getFurnitureMechanic(block)).thenReturn(null);

            assertTrue(BreakerSystem.shouldProtectAdjacentVanillaBlock(player, block));
        } finally {
            BreakerSystem.MODIFIERS.remove(modifier);
        }
    }

    @Test
    void isolatedFullBlockKeepsNativeAttributeBreaking() {
        final Block block = mock(Block.class);
        final Block blockAbove = mock(Block.class);
        final Block blockBelow = mock(Block.class);
        when(block.getRelative(BlockFace.UP)).thenReturn(blockAbove);
        when(block.getRelative(BlockFace.DOWN)).thenReturn(blockBelow);

        try (MockedStatic<OraxenBlocks> blocks = mockStatic(OraxenBlocks.class)) {
            assertFalse(BreakerSystem.hasCustomVerticalNoteBlockNeighbor(block));
        }
    }

    @Test
    void vanillaBlockChangeReplaysTheUnchangedCustomNeighborState() {
        final Block changedBlock = mock(Block.class);
        final Block blockAbove = mock(Block.class);
        final Block blockBelow = mock(Block.class);
        final Location aboveLocation = mock(Location.class);
        final BlockData aboveData = mock(BlockData.class);
        when(changedBlock.getRelative(BlockFace.UP)).thenReturn(blockAbove);
        when(changedBlock.getRelative(BlockFace.DOWN)).thenReturn(blockBelow);
        when(blockAbove.getLocation()).thenReturn(aboveLocation);
        when(blockAbove.getBlockData()).thenReturn(aboveData);

        try (MockedStatic<OraxenBlocks> blocks = mockStatic(OraxenBlocks.class)) {
            blocks.when(() -> OraxenBlocks.isOraxenNoteBlock(blockAbove)).thenReturn(true);
            blocks.when(() -> OraxenBlocks.isOraxenNoteBlock(blockBelow)).thenReturn(false);

            final Map<Location, BlockData> updates =
                    AdjacentNoteBlockUpdateHelper.customVerticalNeighborStates(changedBlock);

            assertEquals(1, updates.size());
            assertSame(aboveData, updates.get(aboveLocation));
        }
    }

    @Test
    void breakCorrectionIncludesAirAndBothUnchangedCustomNeighbors() {
        final Block target = mock(Block.class);
        final Block above = mock(Block.class);
        final Block below = mock(Block.class);
        final Location targetLocation = new Location(null, 0, 15, 0);
        final Location aboveLocation = new Location(null, 0, 16, 0);
        final Location belowLocation = new Location(null, 0, 14, 0);
        final BlockData air = mock(BlockData.class);
        final BlockData aboveData = mock(BlockData.class);
        final BlockData belowData = mock(BlockData.class);
        when(target.getLocation()).thenReturn(targetLocation);
        when(target.getRelative(BlockFace.UP)).thenReturn(above);
        when(target.getRelative(BlockFace.DOWN)).thenReturn(below);
        when(above.getLocation()).thenReturn(aboveLocation);
        when(below.getLocation()).thenReturn(belowLocation);
        when(above.getBlockData()).thenReturn(aboveData);
        when(below.getBlockData()).thenReturn(belowData);

        try (MockedStatic<OraxenBlocks> blocks = mockStatic(OraxenBlocks.class)) {
            blocks.when(() -> OraxenBlocks.isOraxenNoteBlock(above)).thenReturn(true);
            blocks.when(() -> OraxenBlocks.isOraxenNoteBlock(below)).thenReturn(true);

            assertEquals(Map.of(targetLocation, air, aboveLocation, aboveData, belowLocation, belowData),
                    AdjacentNoteBlockUpdateHelper.blockChangeStates(target, air));
        }
    }

    @Test
    void isolatedBreakNeedsNoPredictionCorrection() {
        final Block target = mock(Block.class);
        when(target.getRelative(BlockFace.UP)).thenReturn(mock(Block.class));
        when(target.getRelative(BlockFace.DOWN)).thenReturn(mock(Block.class));

        try (MockedStatic<OraxenBlocks> blocks = mockStatic(OraxenBlocks.class)) {
            assertTrue(AdjacentNoteBlockUpdateHelper.blockChangeStates(target, mock(BlockData.class)).isEmpty());
        }
    }

    @Test
    void suppressesClientMiningWithoutChangingServerPotionEffects() {
        final Player player = mock(Player.class);
        final ArgumentCaptor<PotionEffect> effects = ArgumentCaptor.forClass(PotionEffect.class);

        ClientSideBlockBreakSuppressor.suppress(player);

        verify(player, org.mockito.Mockito.times(2)).sendPotionEffectChange(org.mockito.ArgumentMatchers.eq(player), effects.capture());
        final List<PotionEffectType> effectTypes = effects.getAllValues().stream().map(PotionEffect::getType).toList();
        assertTrue(effectTypes.stream().anyMatch(type -> type.getKey().getKey().equals("mining_fatigue")));
        assertTrue(effectTypes.stream().anyMatch(type -> type.getKey().getKey().equals("haste")));
        verify(player, org.mockito.Mockito.never()).addPotionEffect(org.mockito.ArgumentMatchers.any(PotionEffect.class));
    }

    @Test
    void restoresThePlayersRealPotionEffectAfterBreaking() {
        final Player player = mock(Player.class);
        final PotionEffectType miningFatigue = PotionUtils.getEffectType("mining_fatigue");
        final PotionEffect realEffect = new PotionEffect(miningFatigue, 200, 1);
        when(player.getPotionEffect(argThat(type -> type.getKey().getKey().equals("mining_fatigue"))))
                .thenReturn(realEffect);

        ClientSideBlockBreakSuppressor.restore(player);

        verify(player).sendPotionEffectChangeRemove(org.mockito.ArgumentMatchers.eq(player),
                argThat(type -> type.getKey().getKey().equals("mining_fatigue")));
        verify(player).sendPotionEffectChange(player, realEffect);
    }
}
