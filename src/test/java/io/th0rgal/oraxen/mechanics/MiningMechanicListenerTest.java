package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.mechanics.provided.farming.mining.MiningMechanic;
import io.th0rgal.oraxen.mechanics.provided.farming.mining.MiningMechanicFactory;
import io.th0rgal.oraxen.mechanics.provided.farming.mining.MiningMechanicListener;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MiningMechanicListenerTest extends MechanicTestSupport {

    @Test
    void breaksConfiguredOffsetAndSkipsOrigin() {
        MiningMechanicFactory factory = mock(MiningMechanicFactory.class);
        MiningMechanic mechanic = mock(MiningMechanic.class);
        when(mechanic.getOffsets()).thenReturn(List.of(new MiningMechanic.Offset(0, 0, 0), new MiningMechanic.Offset(-1, 1, 2)));
        when(factory.callEvents()).thenReturn(true);

        World world = mock(World.class);
        Block origin = mock(Block.class);
        Block target = mock(Block.class);
        when(origin.getLocation()).thenReturn(new Location(world, 0, 0, 0));
        when(world.getBlockAt(any(Location.class))).thenReturn(target);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(target);
        when(target.getType()).thenReturn(Material.STONE);
        when(target.getLocation()).thenReturn(new Location(world, -1, 1, 2));

        ItemStack item = mock(ItemStack.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(item);
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);
        when(factory.getMechanic(item)).thenReturn(mechanic);

        Server server = Bukkit.getServer();
        PluginManager previousPluginManager = server.getPluginManager();
        when(server.getTag(anyString(), any(NamespacedKey.class), eq(Material.class)))
                .thenReturn(new Tag<>() {
                    @Override public boolean isTagged(Material material) { return false; }
                    @Override public Set<Material> getValues() { return Set.of(); }
                    @Override public NamespacedKey getKey() { return NamespacedKey.minecraft("test"); }
                });
        when(server.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));

        try {
            new MiningMechanicListener(factory).onBlockBreak(new BlockBreakEvent(origin, player));
            verify(origin, never()).breakNaturally(any(ItemStack.class), eq(true));
            verify(target).breakNaturally(item, true);
        } finally {
            when(server.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
            when(server.getPluginManager()).thenReturn(previousPluginManager);
        }
    }
}
