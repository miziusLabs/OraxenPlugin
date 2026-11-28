package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.api.OraxenBlocks;
import io.th0rgal.oraxen.mechanics.provided.gameplay.block.CustomBlockService;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockMechanicFactory;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockState;
import io.th0rgal.oraxen.utils.BlockHelpers;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StringBlockIsolationTest extends MechanicTestSupport {

    private static final NamespacedKey VARIATION_KEY = new NamespacedKey("oraxen", "string_block_variation");
    private static final NamespacedKey UPPER_KEY = new NamespacedKey("oraxen", "string_block_upper");

    private StringBlockMechanicFactory factory;

    @BeforeAll
    static void initialiseBlockHelpers() throws Exception {
        when(Bukkit.getServer().getTag(anyString(), any(NamespacedKey.class), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Tag<Material> tag = mock(Tag.class);
            when(tag.getValues()).thenReturn(Set.of());
            return tag;
        });
        Class.forName(BlockHelpers.class.getName());
    }

    @AfterEach
    void clearFactory() {
        if (factory != null) StringBlockMechanicFactory.clearInstance(factory);
        CustomBlockService.clearStringBlocks();
    }

    @Test
    void plainTripwireNeverResolvesOrReadsPersistentIdentity() throws Exception {
        enabledFactory();
        try (var mechanics = mockStatic(MechanicsManager.class);
             var helpers = mockStatic(BlockHelpers.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mechanics.when(() -> MechanicsManager.isMechanicEnabled("block")).thenReturn(true);

            for (int variation = 1; variation <= 127; variation++) {
                Block block = mock(Block.class);
                when(block.getType()).thenReturn(Material.TRIPWIRE);
                Tripwire legacyData = legacyTripwire(variation);
                when(block.getBlockData()).thenReturn(legacyData);

                assertNull(OraxenBlocks.getStringMechanic(block));
                verify(block, never()).getBlockData();
                verify(block, never()).getChunk();
            }
            helpers.verify(() -> BlockHelpers.hasPDC(any(Block.class), any(Plugin.class)), never());
        }
    }

    @Test
    void rawBlockDataCannotResolveStringIdentity() {
        assertNull(OraxenBlocks.getStringMechanic(legacyTripwire(17)));
        assertNull(CustomBlockService.getStringBlock(legacyTripwire(17)));
    }

    @Test
    void markedCarrierAndUpperResolveWhileUnmarkedCarrierDoesNot() throws Exception {
        enabledFactory();
        StringBlockMechanic mechanic = mock(StringBlockMechanic.class);
        when(mechanic.isTall()).thenReturn(true);
        CustomBlockService.registerStringBlock(7, mechanic);

        Block base = mock(Block.class);
        Block upper = mock(Block.class);
        Block unmarked = mock(Block.class);
        Block orphanUpper = mock(Block.class);
        when(base.getType()).thenReturn(StringBlockState.MATERIAL);
        when(upper.getType()).thenReturn(StringBlockState.MATERIAL);
        when(unmarked.getType()).thenReturn(StringBlockState.MATERIAL);
        when(orphanUpper.getType()).thenReturn(StringBlockState.MATERIAL);
        when(upper.getRelative(BlockFace.DOWN)).thenReturn(base);
        when(orphanUpper.getRelative(BlockFace.DOWN)).thenReturn(unmarked);

        PersistentDataContainer baseData = mock(PersistentDataContainer.class);
        when(baseData.has(VARIATION_KEY, PersistentDataType.INTEGER)).thenReturn(true);
        when(baseData.getOrDefault(VARIATION_KEY, PersistentDataType.INTEGER, 0)).thenReturn(7);
        PersistentDataContainer upperData = mock(PersistentDataContainer.class);
        when(upperData.has(UPPER_KEY, PersistentDataType.BYTE)).thenReturn(true);
        PersistentDataContainer unmarkedData = mock(PersistentDataContainer.class);
        when(unmarkedData.getOrDefault(VARIATION_KEY, PersistentDataType.INTEGER, 0)).thenReturn(0);

        try (var mechanics = mockStatic(MechanicsManager.class);
             var helpers = mockStatic(BlockHelpers.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mechanics.when(() -> MechanicsManager.isMechanicEnabled("block")).thenReturn(true);
            helpers.when(() -> BlockHelpers.hasPDC(any(Block.class), any(Plugin.class))).thenReturn(true);
            helpers.when(() -> BlockHelpers.getPDC(base)).thenReturn(baseData);
            helpers.when(() -> BlockHelpers.getPDC(upper)).thenReturn(upperData);
            helpers.when(() -> BlockHelpers.getPDC(unmarked)).thenReturn(unmarkedData);
            helpers.when(() -> BlockHelpers.getPDC(orphanUpper)).thenReturn(upperData);

            assertSame(mechanic, OraxenBlocks.getStringMechanic(base));
            assertSame(mechanic, OraxenBlocks.getStringMechanic(upper));
            assertNull(OraxenBlocks.getStringMechanic(unmarked));
            assertNull(OraxenBlocks.getStringMechanic(orphanUpper));
        }
    }

    @Test
    void migrationAndUpperCleanupDoNotClearNeighboringTripwire() throws Exception {
        enabledFactory();
        StringBlockMechanic mechanic = mock(StringBlockMechanic.class);
        when(mechanic.isTall()).thenReturn(true);
        CustomBlockService.registerStringBlock(7, mechanic);

        Block target = mock(Block.class);
        Block upper = mock(Block.class);
        Tripwire targetData = legacyTripwire(7);
        Tripwire upperData = legacyTripwire(3);
        World world = mock(World.class);
        when(world.getMaxHeight()).thenReturn(320);
        when(target.getWorld()).thenReturn(world);
        when(target.getBlockData()).thenReturn(targetData);
        when(target.getRelative(BlockFace.UP)).thenReturn(upper);
        when(upper.getType()).thenReturn(Material.TRIPWIRE);
        when(upper.getBlockData()).thenReturn(upperData);

        try (var helpers = mockStatic(BlockHelpers.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            helpers.when(() -> BlockHelpers.isReplaceable(Material.TRIPWIRE)).thenReturn(false);

            assertFalse(StringBlockState.migrateLegacy(target));
            StringBlockState.clearUpper(target);
        }

        verify(upper, never()).setType(any(Material.class), anyBoolean());
        verify(upper, never()).setBlockData(any(), anyBoolean());
    }

    private StringBlockMechanicFactory enabledFactory() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection section = configuration.createSection("block");
        factory = new StringBlockMechanicFactory(section, false);
        Field enabled = StringBlockMechanicFactory.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        enabled.setBoolean(factory, true);
        return factory;
    }

    private Tripwire legacyTripwire(int variation) {
        Tripwire tripwire = mock(Tripwire.class);
        when(tripwire.hasFace(BlockFace.EAST)).thenReturn((variation & 0x1) != 0);
        when(tripwire.hasFace(BlockFace.WEST)).thenReturn((variation & 0x2) != 0);
        when(tripwire.hasFace(BlockFace.SOUTH)).thenReturn((variation & 0x4) != 0);
        when(tripwire.hasFace(BlockFace.NORTH)).thenReturn((variation & 0x8) != 0);
        when(tripwire.isAttached()).thenReturn((variation & 0x10) != 0);
        when(tripwire.isDisarmed()).thenReturn((variation & 0x20) != 0);
        when(tripwire.isPowered()).thenReturn((variation & 0x40) != 0);
        return tripwire;
    }
}
