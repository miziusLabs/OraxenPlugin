package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.api.events.stringblock.OraxenStringBlockPlaceEvent;
import io.th0rgal.oraxen.mechanics.provided.gameplay.shaped.ShapedBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.shaped.ShapedBlockMechanicFactory;
import io.th0rgal.oraxen.mechanics.provided.gameplay.shaped.ShapedBlockMechanicListener;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockMechanicFactory;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockMechanicListener;
import io.th0rgal.oraxen.protection.AntiGriefLib;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AddedBlockPlacementTest extends MechanicTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {"success", "cancel", "denyBuild", "cancelOraxen"})
    void stackingHonorsPlacementEvents(String outcome) throws Exception {
        Block block = mock(Block.class);
        BlockState state = mock(BlockState.class);
        Tripwire oldData = mock(Tripwire.class);
        Tripwire nextData = mock(Tripwire.class);
        Player player = mock(Player.class);
        ItemStack item = mock(ItemStack.class);
        StringBlockMechanic mechanic = mock(StringBlockMechanic.class);
        when(block.getState()).thenReturn(state);
        when(block.getBlockData()).thenReturn(oldData);
        when(mechanic.getNextStackVariation(0)).thenReturn(new StringBlockMechanic.StackVariation(2, "stack"));
        when(item.getAmount()).thenReturn(3);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        List<Object> events = placementEvents(outcome);

        try (var protection = mockStatic(AntiGriefLib.class);
             var factory = mockStatic(StringBlockMechanicFactory.class)) {
            protection.when(() -> AntiGriefLib.canBuild(eq(player), any())).thenReturn(true);
            factory.when(() -> StringBlockMechanicFactory.getCode(oldData)).thenReturn(0);
            factory.when(() -> StringBlockMechanicFactory.createTripwireData(2)).thenReturn(nextData);
            var method = StringBlockMechanicListener.class.getDeclaredMethod("handleStackInteraction",
                    StringBlockMechanic.class, Block.class, Player.class, ItemStack.class,
                    EquipmentSlot.class);
            method.setAccessible(true);
            assertEquals(true, method.invoke(new StringBlockMechanicListener(), mechanic, block,
                    player, item, EquipmentSlot.HAND));
        }

        BlockPlaceEvent place = (BlockPlaceEvent) events.getFirst();
        assertSame(state, place.getBlockReplacedState());
        assertSame(block, place.getBlockAgainst());
        assertSame(EquipmentSlot.HAND, place.getHand());
        assertEquals(outcome.equals("cancel") || outcome.equals("denyBuild") ? 1 : 2, events.size());
        if (outcome.equals("success")) {
            verify(item).setAmount(2);
            verify(mechanic).runPlaceEvents(player);
            verify(block, never()).setBlockData(oldData, false);
        } else {
            verify(block).setBlockData(oldData, false);
            verify(item, never()).setAmount(anyInt());
            verify(mechanic, never()).runPlaceEvents(any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "cancel", "denyBuild"})
    void doublingSlabsHonorsPlacementEvents(String outcome) throws Exception {
        Block block = mock(Block.class);
        BlockState state = mock(BlockState.class);
        Slab slab = mock(Slab.class);
        BlockData oldData = mock(BlockData.class);
        Player player = mock(Player.class);
        ItemStack item = mock(ItemStack.class);
        PlayerInteractEvent interaction = mock(PlayerInteractEvent.class);
        ShapedBlockMechanic mechanic = mock(ShapedBlockMechanic.class);
        ShapedBlockMechanicFactory factory = mock(ShapedBlockMechanicFactory.class);
        when(factory.getMechanicFromBlock(block)).thenReturn(mechanic);
        when(block.getType()).thenReturn(Material.WAXED_CUT_COPPER_SLAB);
        when(mechanic.getPlacedMaterial()).thenReturn(Material.WAXED_CUT_COPPER_SLAB);
        when(block.getState()).thenReturn(state);
        when(block.getBlockData()).thenReturn(slab);
        when(slab.getType()).thenReturn(Slab.Type.BOTTOM);
        when(slab.clone()).thenReturn(oldData);
        when(interaction.getHand()).thenReturn(EquipmentSlot.HAND);
        when(block.getWorld()).thenReturn(mock(World.class));
        when(item.getAmount()).thenReturn(3);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        List<Object> events = placementEvents(outcome);

        try (var protection = mockStatic(AntiGriefLib.class)) {
            protection.when(() -> AntiGriefLib.canBuild(eq(player), any())).thenReturn(true);
            var method = ShapedBlockMechanicListener.class.getDeclaredMethod("handleSlabPlacement",
                    PlayerInteractEvent.class, Player.class, ItemStack.class, ShapedBlockMechanic.class,
                    Block.class, BlockFace.class, Block.class);
            method.setAccessible(true);
            assertEquals(true, method.invoke(new ShapedBlockMechanicListener(factory), interaction,
                    player, item, mechanic, block, BlockFace.UP, block));
        }

        assertEquals(1, events.size());
        assertSame(state, ((BlockPlaceEvent) events.getFirst()).getBlockReplacedState());
        verify(interaction).setCancelled(true);
        if (outcome.equals("success")) {
            verify(item).setAmount(2);
            verify(mechanic).runPlaceEvents(player);
            verify(block, never()).setBlockData(oldData);
        } else {
            verify(block).setBlockData(oldData);
            verify(item, never()).setAmount(anyInt());
            verify(mechanic, never()).runPlaceEvents(any());
        }
    }

    private List<Object> placementEvents(String outcome) {
        List<Object> events = new ArrayList<>();
        PluginManager manager = mock(PluginManager.class);
        when(Bukkit.getServer().getPluginManager()).thenReturn(manager);
        doAnswer(invocation -> {
            Object event = invocation.getArgument(0);
            events.add(event);
            if (event instanceof BlockPlaceEvent place) {
                if (outcome.equals("cancel")) place.setCancelled(true);
                if (outcome.equals("denyBuild")) place.setBuild(false);
            }
            if (event instanceof OraxenStringBlockPlaceEvent place && outcome.equals("cancelOraxen"))
                place.setCancelled(true);
            return null;
        }).when(manager).callEvent(any());
        return events;
    }
}
