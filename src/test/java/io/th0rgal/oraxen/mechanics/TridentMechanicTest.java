package io.th0rgal.oraxen.mechanics;

import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.api.events.OraxenPackGeneratedEvent;
import io.th0rgal.oraxen.mechanics.provided.combat.trident.TridentMechanic;
import io.th0rgal.oraxen.mechanics.provided.combat.trident.TridentMechanicFactory;
import io.th0rgal.oraxen.mechanics.provided.combat.trident.TridentMechanicListener;
import io.th0rgal.oraxen.utils.VersionUtil;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.VirtualFile;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TridentMechanicTest extends MechanicTestSupport {
    @Test
    void parsesRequestedConfigurationAndFallbacks() {
        TridentMechanic mechanic = mechanic();
        assertEquals("custom.throw", mechanic.getThrowSound());
        assertEquals("custom.hit", mechanic.getHitSound());
        assertEquals("custom.hit", mechanic.getHitGroundSound());
        assertEquals("custom.throw", mechanic.getReturnSound());
        assertEquals("minecraft:tridents/oraxen_trident", mechanic.getModel());
        assertEquals("minecraft:tridents/oraxen_trident_thrown", mechanic.getThrownModel());
    }

    @Test
    void explicitSoundsOverrideFallbacksAndModelsAcceptNamespaces() {
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), mechanicSection("trident",
                "sounds.hit-ground", "custom.ground", "sounds.return", "custom.return",
                "appearance.model", "example:tridents/held.json"));
        assertEquals("custom.ground", mechanic.getHitGroundSound());
        assertEquals("custom.return", mechanic.getReturnSound());
        assertEquals("example:tridents/held", mechanic.getModel());
        assertNull(mechanic.getThrownModel());
    }

    @Test
    void absentSoundsLeaveNativeAudioAlone() {
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), mechanicSection("trident"));
        assertNull(mechanic.getThrowSound());
        assertNull(mechanic.getHitSound());
        assertNull(mechanic.getHitGroundSound());
        assertNull(mechanic.getReturnSound());
    }

    @Test
    void readsEveryDisplayTransformCaseInsensitively() {
        for (ItemDisplayTransform transform : ItemDisplayTransform.values()) {
            TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), mechanicSection("trident",
                    "appearance.transform", transform.name().toLowerCase(java.util.Locale.ROOT)));
            assertEquals(transform, mechanic.getTransform());
        }
    }

    @Test
    void missingAndInvalidTransformsUseNone() {
        assertEquals(ItemDisplayTransform.NONE, mechanic().getTransform());
        TridentMechanic mechanic = new TridentMechanic(mechanicFactory(), mechanicSection("trident",
                "appearance.transform", "not_a_transform"));
        assertEquals(ItemDisplayTransform.NONE, mechanic.getTransform());
    }

    @Test
    void rejectsInvalidModelPaths() {
        for (String path : new String[]{"", "../Invalid Model", "test:UPPERCASE"}) {
            assertThrows(IllegalArgumentException.class, () -> new TridentMechanic(mechanicFactory(),
                    mechanicSection("trident", "appearance.model", path)));
        }
    }

    @Test
    void itemModifierMakesARealTridentAndGatesModernModels() {
        TridentMechanic mechanic = mechanic();
        try (MockedStatic<VersionUtil> version = mockStatic(VersionUtil.class)) {
            ItemBuilder legacy = mock(ItemBuilder.class);
            mechanic.getItemModifiers()[0].apply(legacy);
            verify(legacy).setType(Material.TRIDENT);
            verify(legacy, never()).setItemModel(any());

            version.when(() -> VersionUtil.atOrAbove("1.21.4")).thenReturn(true);
            ItemBuilder modern = mock(ItemBuilder.class);
            mechanic.getItemModifiers()[0].apply(modern);
            verify(modern).setType(Material.TRIDENT);
            verify(modern).setItemModel(TridentMechanic.modelKey("test_item", false));
        }
    }

    @Test
    void generatesGeometryModelInsteadOfHardcodedTridentRenderer() {
        var definition = TridentMechanicFactory.createModelDefinition("example:tridents/held");
        var model = definition.getAsJsonObject("model");
        assertEquals("minecraft:model", model.get("type").getAsString());
        assertEquals("example:tridents/held", model.get("model").getAsString());
        assertNotEquals(TridentMechanic.modelKey("test_item", false),
                TridentMechanic.modelKey("test_item", true));
    }

    @Test
    void routesSoundsWithoutChangingPickupItemOrDamage() {
        TridentMechanicFactory factory = mock(TridentMechanicFactory.class);
        TridentMechanicListener listener = new TridentMechanicListener(factory);
        Trident trident = mock(Trident.class);
        ItemStack stack = mock(ItemStack.class);
        when(trident.getItemStack()).thenReturn(stack);
        when(factory.getMechanic(stack)).thenReturn(mechanic());
        World world = mock(World.class);
        Location location = new Location(world, 1, 2, 3);
        when(trident.getLocation()).thenReturn(location);

        listener.onLaunch(new ProjectileLaunchEvent(trident));
        verify(world).playSound(location, "custom.throw", 1, 1);
        ProjectileHitEvent hit = mock(ProjectileHitEvent.class);
        when(hit.getEntity()).thenReturn(trident);
        when(hit.getHitEntity()).thenReturn(mock(Entity.class));
        listener.onHit(hit);
        verify(world).playSound(location, "custom.hit", 1, 1);

        when(hit.getHitEntity()).thenReturn(null);
        when(hit.getHitBlock()).thenReturn(mock(Block.class));
        listener.onHit(hit);
        verify(world, times(2)).playSound(location, "custom.hit", 1, 1);

        Player player = mock(Player.class);
        when(player.getLocation()).thenReturn(location);
        PlayerPickupArrowEvent pickup = mock(PlayerPickupArrowEvent.class);
        when(pickup.getArrow()).thenReturn(trident);
        when(pickup.getPlayer()).thenReturn(player);
        listener.onPickup(pickup);
        verify(world, times(2)).playSound(location, "custom.throw", 1, 1);
        verify(trident, never()).setItemStack(any());
        verify(trident, never()).setDamage(anyDouble());
        verify(pickup, never()).setCancelled(anyBoolean());
    }

    @Test
    void ignoresOrdinaryTridents() {
        TridentMechanicFactory factory = mock(TridentMechanicFactory.class);
        TridentMechanicListener listener = new TridentMechanicListener(factory);
        Trident trident = mock(Trident.class);
        listener.onLaunch(new ProjectileLaunchEvent(trident));
        verify(trident, never()).getLocation();
    }

    @Test
    void cancelledEventsDoNotPlaySounds() {
        TridentMechanicFactory factory = mock(TridentMechanicFactory.class);
        TridentMechanicListener listener = new TridentMechanicListener(factory);
        ProjectileLaunchEvent launch = new ProjectileLaunchEvent(mock(Trident.class));
        launch.setCancelled(true);
        listener.onLaunch(launch);
        ProjectileHitEvent hit = mock(ProjectileHitEvent.class);
        when(hit.isCancelled()).thenReturn(true);
        listener.onHit(hit);
        PlayerPickupArrowEvent pickup = mock(PlayerPickupArrowEvent.class);
        when(pickup.isCancelled()).thenReturn(true);
        listener.onPickup(pickup);
        verifyNoInteractions(factory);
    }

    @Test
    void reloadPreventsPendingSpawnsFromCreatingOrphanDisplays() {
        TridentMechanicFactory factory = mock(TridentMechanicFactory.class);
        TridentMechanicListener listener = new TridentMechanicListener(factory);
        Trident trident = mock(Trident.class);
        ItemStack item = mock(ItemStack.class);
        when(trident.getItemStack()).thenReturn(item);
        when(factory.getMechanic(item)).thenReturn(mechanic());
        when(trident.isValid()).thenReturn(true);
        AtomicReference<Runnable> pending = new AtomicReference<>();
        try (MockedStatic<VersionUtil> version = mockStatic(VersionUtil.class);
             MockedStatic<SchedulerUtil> scheduler = mockStatic(SchedulerUtil.class)) {
            version.when(() -> VersionUtil.atOrAbove("1.21.4")).thenReturn(true);
            scheduler.when(() -> SchedulerUtil.runForEntity(eq(trident), any(Runnable.class)))
                    .thenAnswer(call -> { pending.set(call.getArgument(1)); return null; });
            listener.onAdd(new EntityAddToWorldEvent(trident, mock(World.class)));
            assertNotNull(pending.get());
            listener.close();
            pending.get().run();
            verify(trident, never()).getLocation();
            verify(trident, never()).setVisibleByDefault(false);
        }
    }

    @Test
    void packGenerationIncludesBothModelsWithoutAPackSectionAndDoesNotDuplicateFiles() throws IOException {
        try (MockedStatic<MechanicsManager> manager = mockStatic(MechanicsManager.class)) {
            TridentMechanicFactory factory = new TridentMechanicFactory(standaloneSection());
            factory.parse(mechanic().getSection());
            ArrayList<VirtualFile> output = new ArrayList<>();
            OraxenPackGeneratedEvent event = new OraxenPackGeneratedEvent(output);
            factory.onPackGeneration(event);
            factory.onPackGeneration(event);
            assertEquals(2, output.size());
            assertEquals("assets/oraxen/items/trident/test_item/held.json", output.get(0).getPath());
            assertEquals("minecraft:tridents/oraxen_trident", JsonParser.parseString(new String(
                    output.get(0).getInputStream().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonObject("model").get("model").getAsString());
            assertEquals("assets/oraxen/items/trident/test_item/thrown.json", output.get(1).getPath());
            assertEquals("minecraft:tridents/oraxen_trident_thrown", JsonParser.parseString(new String(
                    output.get(1).getInputStream().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject()
                    .getAsJsonObject("model").get("model").getAsString());
        }
    }

    private TridentMechanic mechanic() {
        return new TridentMechanic(mechanicFactory(), mechanicSection("trident",
                "sounds.throw", "custom.throw", "sounds.hit", "custom.hit",
                "appearance.model", "tridents/oraxen_trident",
                "appearance.thrown-model", "tridents/oraxen_trident_thrown"));
    }
}
