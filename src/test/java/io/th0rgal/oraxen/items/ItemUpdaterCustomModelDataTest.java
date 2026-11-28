package io.th0rgal.oraxen.items;

import io.th0rgal.oraxen.utils.CustomModelDataHelper;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Color;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@Isolated
class ItemUpdaterCustomModelDataTest {

    @Test
    void keepsAllConfiguredModernFieldsAndFractionalFloats() {
        ItemMeta configured = mock(ItemMeta.class);
        ItemMeta target = mock(ItemMeta.class);
        ItemMeta old = mock(ItemMeta.class);
        CustomModelDataComponent component = component();
        when(configured.hasCustomModelDataComponent()).thenReturn(true);
        when(target.getCustomModelDataComponent()).thenReturn(component);

        try (MockedStatic<VersionUtil> versions = mockStatic(VersionUtil.class)) {
            versions.when(() -> VersionUtil.atOrAbove("1.21.4")).thenReturn(true);
            ItemUpdater.updateCustomModelData(target, configured, old);
        }

        verify(target, never()).setCustomModelDataComponent(any());
        verify(target, never()).setCustomModelData(any());
        assertEquals(new CustomModelDataHelper.ComponentData(
                List.of("alpha", "beta"), List.of(1.0f, 2.5f), List.of(true, false), List.of(Color.RED)),
                CustomModelDataHelper.getComponentData(target));
    }

    @Test
    void inheritsTheEntireOldModernComponentWhenNotConfigured() {
        ItemMeta configured = mock(ItemMeta.class);
        ItemMeta target = mock(ItemMeta.class);
        ItemMeta old = mock(ItemMeta.class);
        CustomModelDataComponent component = component();
        when(old.hasCustomModelDataComponent()).thenReturn(true);
        when(old.getCustomModelDataComponent()).thenReturn(component);

        try (MockedStatic<VersionUtil> versions = mockStatic(VersionUtil.class)) {
            versions.when(() -> VersionUtil.atOrAbove("1.21.4")).thenReturn(true);
            ItemUpdater.updateCustomModelData(target, configured, old);
        }

        verify(target).setCustomModelDataComponent(component);
        verify(target, never()).setCustomModelData(any());
        assertEquals(new CustomModelDataHelper.ComponentData(
                List.of("alpha", "beta"), List.of(1.0f, 2.5f), List.of(true, false), List.of(Color.RED)),
                CustomModelDataHelper.getComponentData(old));
    }

    @Test
    void configuredComponentWithoutFloatsStillOverridesOldData() {
        ItemMeta configured = mock(ItemMeta.class);
        ItemMeta target = mock(ItemMeta.class);
        ItemMeta old = mock(ItemMeta.class);
        when(configured.hasCustomModelDataComponent()).thenReturn(true);

        try (MockedStatic<VersionUtil> versions = mockStatic(VersionUtil.class)) {
            versions.when(() -> VersionUtil.atOrAbove("1.21.4")).thenReturn(true);
            ItemUpdater.updateCustomModelData(target, configured, old);
        }

        verifyNoInteractions(old);
        verify(target, never()).setCustomModelDataComponent(any());
    }

    private static CustomModelDataComponent component() {
        CustomModelDataComponent component = mock(CustomModelDataComponent.class);
        when(component.getFloats()).thenReturn(List.of(1.0f, 2.5f));
        when(component.getStrings()).thenReturn(List.of("alpha", "beta"));
        when(component.getFlags()).thenReturn(List.of(true, false));
        when(component.getColors()).thenReturn(List.of(Color.RED));
        return component;
    }
}
