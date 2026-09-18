package io.th0rgal.oraxen.nms.handler.java21;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.utils.PacketHelpers;
import io.th0rgal.oraxen.utils.logs.Logs;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;

final class PacketHandler {

    private final NamespacedKey key;
    private volatile boolean formatInventoryTitles;
    private volatile boolean formatTitles;
    private volatile boolean hideScoreboardNumbers;

    PacketHandler() {
        key = new NamespacedKey(OraxenPlugin.get(), "packet_formatting");
        ChannelInitializeListenerHolder.removeListener(key);
        ChannelInitializeListenerHolder.addListener(key, this::install);
        for (var player : Bukkit.getOnlinePlayers())
            install(((CraftPlayer) player).getHandle().connection.connection.channel);
    }

    private void install(Channel channel) {
        var pipeline = channel.pipeline();
        if (pipeline.get(key.asString()) != null) pipeline.remove(key.asString());
        pipeline.addBefore("packet_handler", key.asString(), new ChannelDuplexHandler() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ctx.write(transform(msg), promise);
            }
        });
    }

    void shutdown() {
        ChannelInitializeListenerHolder.removeListener(key);
        for (var player : Bukkit.getOnlinePlayers()) {
            Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
            if (channel.pipeline().get(key.asString()) != null) channel.pipeline().remove(key.asString());
        }
    }

    void formatInventoryTitles(boolean enabled) {
        formatInventoryTitles = enabled;
    }

    void formatTitles(boolean enabled) {
        formatTitles = enabled;
    }

    void hideScoreboardNumbers(boolean enabled) {
        hideScoreboardNumbers = enabled;
    }

    private Object transform(Object packet) {
        try {
            if (formatInventoryTitles && packet instanceof ClientboundOpenScreenPacket openScreen) {
                return new ClientboundOpenScreenPacket(openScreen.getContainerId(), openScreen.getType(), transform(openScreen.getTitle()));
            }
            if (formatTitles && packet instanceof ClientboundSetTitleTextPacket title && Settings.FORMAT_TITLES.toBool()) {
                return new ClientboundSetTitleTextPacket(transform(title.text()));
            }
            if (formatTitles && packet instanceof ClientboundSetSubtitleTextPacket subtitle && Settings.FORMAT_SUBTITLES.toBool()) {
                return new ClientboundSetSubtitleTextPacket(transform(subtitle.text()));
            }
            if (formatTitles && packet instanceof ClientboundSetActionBarTextPacket actionBar && Settings.FORMAT_ACTION_BAR.toBool()) {
                return new ClientboundSetActionBarTextPacket(transform(actionBar.text()));
            }
            if (hideScoreboardNumbers && packet instanceof ClientboundSetObjectivePacket objective
                    && objective.getMethod() != ClientboundSetObjectivePacket.METHOD_REMOVE) {
                Objective nativeObjective = new Objective(new Scoreboard(), objective.getObjectiveName(), ObjectiveCriteria.DUMMY,
                        objective.getDisplayName(), objective.getRenderType(), false, BlankFormat.INSTANCE);
                return new ClientboundSetObjectivePacket(nativeObjective, objective.getMethod());
            }
        } catch (Throwable exception) {
            if (Settings.DEBUG.toBool())
                Logs.logWarning("Failed to transform outgoing packet " + packet.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        return packet;
    }

    private static Component transform(Component component) {
        return PaperAdventure.asVanilla(PacketHelpers.translateTitle(PaperAdventure.asAdventure(component)));
    }
}
