package io.th0rgal.oraxen.packets;

import io.th0rgal.oraxen.nms.NMSHandler;
import io.th0rgal.oraxen.nms.NMSHandlers;
import io.th0rgal.oraxen.utils.SnapshotVersion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

public class NativePacketAdapter implements PacketAdapter {

    private NMSHandler handler() {
        return NMSHandlers.getHandler();
    }

    @Override
    public boolean isEnabled() {
        return handler().supportsNativePacketHandling();
    }

    @Override
    public void registerInventoryListener() {
        handler().formatInventoryTitles(true);
    }

    @Override
    public void registerScoreboardListener() {
        handler().hideScoreboardNumbers(true);
    }

    @Override
    public void registerTitleListener() {
        handler().formatTitles(true);
    }

    @Override
    public void removeInventoryListener() {
        handler().formatInventoryTitles(false);
    }

    @Override
    public void removeTitleListener() {
        handler().formatTitles(false);
    }

    @Override
    public String getLatestMCVersion() {
        return PacketAdapter.EmptyAdapter.latestMCVersion(Bukkit.getMinecraftVersion());
    }

    @Override
    public boolean isNewer(SnapshotVersion snapshot) {
        return true;
    }

    @Nullable
    @Override
    public Plugin getPlugin() {
        return null;
    }
}
