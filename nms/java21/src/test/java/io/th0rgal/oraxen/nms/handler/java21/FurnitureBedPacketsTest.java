package io.th0rgal.oraxen.nms.handler.java21;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.utils.ExecutionSchedulerPort;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class FurnitureBedPacketsTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void resetScheduler() {
        SchedulerUtil.resetBackend();
    }

    private record Fixture(FurnitureBedPackets packets, CraftPlayer sleeper, ServerPlayer sleeperHandle,
                           ServerGamePacketListenerImpl sleeperConnection, CraftPlayer viewer, ServerPlayer viewerHandle,
                           ServerGamePacketListenerImpl viewerConnection, CraftWorld bedWorld, UUID bedWorldId,
                           CraftBlockData fakeBedData, BlockState fakeBedState, BlockPos expectedBedPos,
                           Location headLocation) {
    }

    private Fixture createFixture(BlockState initialWorldState) {
        UUID bedWorldId = UUID.randomUUID();
        CraftWorld bedWorld = mock(CraftWorld.class);
        when(bedWorld.getUID()).thenReturn(bedWorldId);
        when(bedWorld.getMinHeight()).thenReturn(-64);
        when(bedWorld.getMaxHeight()).thenReturn(320);
        when(bedWorld.isChunkLoaded(0, 0)).thenReturn(true);

        Block headBlock = mock(Block.class);
        when(headBlock.getType()).thenReturn(Material.AIR);
        Location headLocation = new Location(bedWorld, 5.5, 72.0, 5.5);
        when(bedWorld.getBlockAt(headLocation)).thenReturn(headBlock);

        CraftBlockData initialData = mock(CraftBlockData.class);
        when(initialData.getState()).thenReturn(initialWorldState);
        when(bedWorld.getBlockData(any(Location.class))).thenReturn(initialData);

        BlockState fakeBedState = Blocks.RED_BED.defaultBlockState();
        CraftBlockData fakeBedData = mock(CraftBlockData.class, withSettings().extraInterfaces(org.bukkit.block.data.type.Bed.class));
        when(fakeBedData.getState()).thenReturn(fakeBedState);

        ServerLevel bedLevel = mock(ServerLevel.class);
        when(bedLevel.getWorld()).thenReturn(bedWorld);

        ServerGamePacketListenerImpl sleeperConn = mock(ServerGamePacketListenerImpl.class);
        ServerPlayer sleeperHandle = mock(ServerPlayer.class);
        sleeperHandle.connection = sleeperConn;
        when(sleeperHandle.getId()).thenReturn(10);
        when(sleeperHandle.position()).thenReturn(new Vec3(5.5, 72.0, 5.5));
        when(sleeperHandle.getSleepingPos()).thenReturn(Optional.empty());
        when(sleeperHandle.level()).thenReturn(bedLevel);

        UUID sleeperId = UUID.randomUUID();
        CraftPlayer sleeper = mock(CraftPlayer.class);
        when(sleeper.getUniqueId()).thenReturn(sleeperId);
        when(sleeper.getWorld()).thenReturn(bedWorld);
        when(sleeper.getHandle()).thenReturn(sleeperHandle);

        ServerGamePacketListenerImpl viewerConn = mock(ServerGamePacketListenerImpl.class);
        ServerPlayer viewerHandle = mock(ServerPlayer.class);
        viewerHandle.connection = viewerConn;
        UUID viewerId = UUID.randomUUID();
        when(viewerHandle.getId()).thenReturn(20);
        when(viewerHandle.getUUID()).thenReturn(viewerId);
        when(viewerHandle.level()).thenReturn(bedLevel);

        CraftPlayer viewer = mock(CraftPlayer.class);
        when(viewer.getUniqueId()).thenReturn(viewerId);
        when(viewer.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(bedWorld);
        when(viewer.getHandle()).thenReturn(viewerHandle);

        when(sleeper.getTrackedBy()).thenReturn(Set.of(viewer));

        // headY=72 is closer to min=-64 (136) than top=319 (247), so bedY picks top=319.
        BlockPos expectedBedPos = new BlockPos(5, 319, 5);
        return new Fixture(new FurnitureBedPackets(), sleeper, sleeperHandle, sleeperConn,
                viewer, viewerHandle, viewerConn, bedWorld, bedWorldId, fakeBedData, fakeBedState,
                expectedBedPos, headLocation);
    }

    @Test
    void crossRegionViewerSendsAreScheduledOnViewersOwningThread() {
        Fixture f = createFixture(Blocks.AIR.defaultBlockState());
        OraxenPlugin plugin = mock(OraxenPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        List<Runnable> viewerTasks = new ArrayList<>();
        ExecutionSchedulerPort scheduler = mock(ExecutionSchedulerPort.class);
        when(scheduler.runForEntity(any(), eq(f.viewer()), any(Runnable.class), any())).thenAnswer(inv -> {
            viewerTasks.add(inv.getArgument(2));
            return null;
        });
        SchedulerUtil.setBackend(scheduler);

        try (var oraxen = mockStatic(OraxenPlugin.class);
             var bukkit = mockStatic(Bukkit.class)) {
            oraxen.when(OraxenPlugin::get).thenReturn(plugin);
            bukkit.when(() -> Bukkit.createBlockData(Material.RED_BED)).thenReturn(f.fakeBedData());
            bukkit.when(() -> Bukkit.getWorld(f.bedWorldId())).thenReturn(f.bedWorld());
            bukkit.when(() -> Bukkit.getPlayer(f.viewer().getUniqueId())).thenReturn(f.viewer());
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(false);

            f.packets().show(f.sleeper(), f.headLocation(), 0f);
            // Viewer is in another region, so show() must schedule instead of sending directly.
            verify(f.viewerConnection(), never()).send(any());
            assertEquals(1, viewerTasks.size());
            viewerTasks.remove(0).run();
            verify(f.viewerConnection(), times(1)).send(any(ClientboundBundlePacket.class));

            f.packets().hide(f.sleeper());
            // hide() must also schedule viewer reset on the viewer's thread rather than sending directly.
            verify(f.viewerConnection(), times(1)).send(any());
            assertEquals(1, viewerTasks.size());
            viewerTasks.remove(0).run();
            verify(f.viewerConnection(), times(2)).send(any(ClientboundBundlePacket.class));
        }
    }

    @Test
    void transformBlockUpdateIgnoresViewerInDifferentWorld() throws Exception {
        Fixture f = createFixture(Blocks.AIR.defaultBlockState());
        OraxenPlugin plugin = mock(OraxenPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        try (var oraxen = mockStatic(OraxenPlugin.class);
             var bukkit = mockStatic(Bukkit.class)) {
            oraxen.when(OraxenPlugin::get).thenReturn(plugin);
            bukkit.when(() -> Bukkit.createBlockData(Material.RED_BED)).thenReturn(f.fakeBedData());
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);

            f.packets().show(f.sleeper(), f.headLocation(), 0f);

            ClientboundBlockUpdatePacket update = new ClientboundBlockUpdatePacket(
                    f.expectedBedPos(), Blocks.STONE.defaultBlockState());

            // In the bed's world, the update is rewritten to preserve the fake red bed.
            Object sameWorld = f.packets().transform(update, f.viewerHandle());
            ClientboundBlockUpdatePacket rewritten = assertInstanceOf(ClientboundBlockUpdatePacket.class, sameWorld);
            assertEquals(f.fakeBedState(), rewritten.getBlockState());

            // Once the viewer moves to another world, matching coordinates there must not be replaced.
            CraftWorld otherWorld = mock(CraftWorld.class);
            when(otherWorld.getUID()).thenReturn(UUID.randomUUID());
            ServerLevel otherLevel = mock(ServerLevel.class);
            when(otherLevel.getWorld()).thenReturn(otherWorld);
            when(f.viewerHandle().level()).thenReturn(otherLevel);

            Object otherWorldResult = f.packets().transform(update, f.viewerHandle());
            assertSame(update, otherWorldResult);
        }
    }

    @Test
    void forgetReadsCurrentBlockStateOnOwningRegionThreadInsteadOfStaleSnapshot() {
        Fixture f = createFixture(Blocks.AIR.defaultBlockState());
        OraxenPlugin plugin = mock(OraxenPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        List<Runnable> regionTasks = new ArrayList<>();
        List<Runnable> viewerTasks = new ArrayList<>();
        ExecutionSchedulerPort scheduler = mock(ExecutionSchedulerPort.class);
        when(scheduler.runAtLocation(any(), any(Location.class), any(Runnable.class))).thenAnswer(inv -> {
            regionTasks.add(inv.getArgument(2));
            return null;
        });
        when(scheduler.runForEntity(any(), eq(f.viewer()), any(Runnable.class), any())).thenAnswer(inv -> {
            viewerTasks.add(inv.getArgument(2));
            return null;
        });
        SchedulerUtil.setBackend(scheduler);

        try (var oraxen = mockStatic(OraxenPlugin.class);
             var bukkit = mockStatic(Bukkit.class)) {
            oraxen.when(OraxenPlugin::get).thenReturn(plugin);
            bukkit.when(() -> Bukkit.createBlockData(Material.RED_BED)).thenReturn(f.fakeBedData());
            bukkit.when(() -> Bukkit.getWorld(f.bedWorldId())).thenReturn(f.bedWorld());
            bukkit.when(() -> Bukkit.getPlayer(f.viewer().getUniqueId())).thenReturn(f.viewer());
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);

            f.packets().show(f.sleeper(), f.headLocation(), 0f);

            // Real block at bedPos changes while the player sleeps.
            BlockState updatedState = Blocks.OBSIDIAN.defaultBlockState();
            CraftBlockData updatedData = mock(CraftBlockData.class);
            when(updatedData.getState()).thenReturn(updatedState);
            when(f.bedWorld().getBlockData(any(Location.class))).thenReturn(updatedData);

            // Simulate forget() called from a thread that does not own bedLocation or viewer.
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(false);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(false);

            f.packets().forget(f.sleeperHandle().getId());
            assertEquals(1, regionTasks.size());
            verify(f.viewerConnection(), times(1)).send(any()); // Only the initial show() sync so far.

            regionTasks.remove(0).run();
            assertEquals(1, viewerTasks.size());
            viewerTasks.remove(0).run();

            ArgumentCaptor<Packet<?>> sent = ArgumentCaptor.forClass(Packet.class);
            verify(f.viewerConnection(), times(2)).send(sent.capture());
            ClientboundBlockUpdatePacket restore = assertInstanceOf(
                    ClientboundBlockUpdatePacket.class, sent.getAllValues().get(1));
            assertEquals(f.expectedBedPos(), restore.getPos());
            assertEquals(updatedState, restore.getBlockState());
        }
    }
    @Test
    void hideRestoresBarrierAddedAtHeadPosDuringSleep() throws Exception {
        Fixture f = createFixture(Blocks.AIR.defaultBlockState());
        OraxenPlugin plugin = mock(OraxenPlugin.class);
        when(plugin.isEnabled()).thenReturn(true);

        try (var oraxen = mockStatic(OraxenPlugin.class);
             var bukkit = mockStatic(Bukkit.class)) {
            oraxen.when(OraxenPlugin::get).thenReturn(plugin);
            bukkit.when(() -> Bukkit.createBlockData(Material.RED_BED)).thenReturn(f.fakeBedData());
            bukkit.when(() -> Bukkit.getWorld(f.bedWorldId())).thenReturn(f.bedWorld());
            bukkit.when(() -> Bukkit.getPlayer(f.viewer().getUniqueId())).thenReturn(f.viewer());
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Location.class))).thenReturn(true);
            bukkit.when(() -> Bukkit.isOwnedByCurrentRegion(any(Entity.class))).thenReturn(true);

            f.packets().show(f.sleeper(), f.headLocation(), 0f);

            // A barrier is placed at headPos after sleep started; transform hides it from the sleeper.
            BlockPos headPos = BlockPos.containing(f.headLocation().getX(), f.headLocation().getY(), f.headLocation().getZ());
            ClientboundBlockUpdatePacket barrierPlaced = new ClientboundBlockUpdatePacket(
                    headPos, Blocks.BARRIER.defaultBlockState());
            Object transformed = f.packets().transform(barrierPlaced, f.sleeperHandle());
            ClientboundBlockUpdatePacket hidden = assertInstanceOf(ClientboundBlockUpdatePacket.class, transformed);
            assertEquals(Blocks.AIR.defaultBlockState(), hidden.getBlockState());

            CraftBlockData barrierData = mock(CraftBlockData.class);
            when(barrierData.getState()).thenReturn(Blocks.BARRIER.defaultBlockState());
            Location headBlockLoc = new Location(f.bedWorld(), headPos.getX(), headPos.getY(), headPos.getZ());
            when(f.bedWorld().getBlockData(headBlockLoc)).thenReturn(barrierData);

            f.packets().hide(f.sleeper());
            verify(f.sleeper()).sendBlockChange(headBlockLoc, barrierData);
        }
    }
}
