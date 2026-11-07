package io.th0rgal.oraxen.nms.handler.java25;

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
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PacketHandler {

    private final NamespacedKey key;
    private final TridentPacketHandler tridents = new TridentPacketHandler();
    private volatile boolean formatInventoryTitles;
    private volatile boolean formatTitles;
    private volatile boolean formatGlyphTags;
    private volatile boolean hideScoreboardNumbers;

    PacketHandler() {
        key = new NamespacedKey(OraxenPlugin.get(), "packet_formatting");
        ChannelInitializeListenerHolder.removeListener(key);
        ChannelInitializeListenerHolder.addListener(key, this::install);
        for (var player : Bukkit.getOnlinePlayers()) {
            try {
                install(((CraftPlayer) player).getHandle().connection.connection.channel);
            } catch (RuntimeException ignored) {
                // The connection closed concurrently, it no longer needs the handler
            }
        }
    }

    private void install(Channel channel) {
        var pipeline = channel.pipeline();
        if (pipeline.get(key.asString()) != null) pipeline.remove(key.asString());
        pipeline.addBefore("packet_handler", key.asString(), new ChannelDuplexHandler() {
            private final TridentPacketHandler.Session tridentSession = tridents.session();
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                try {
                    msg = tridentSession.transform(msg);
                } catch (ReflectiveOperationException | LinkageError exception) {
                    Logs.logWarning("Failed to render custom trident.\n" + exception.getMessage());
                }
                ctx.write(transform(msg), promise);
            }
        });
    }

    void shutdown() {
        tridents.shutdown();
        ChannelInitializeListenerHolder.removeListener(key);
        for (var player : Bukkit.getOnlinePlayers()) {
            try {
                Channel channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
                if (channel.pipeline().get(key.asString()) != null) channel.pipeline().remove(key.asString());
            } catch (RuntimeException ignored) {
                // A closing channel may drop the handler between get and remove; keep cleaning the others
            }
        }
    }

    void formatInventoryTitles(boolean enabled) {
        formatInventoryTitles = enabled;
    }

    void formatTitles(boolean enabled) {
        formatTitles = enabled;
    }

    void formatGlyphTags(boolean enabled) {
        formatGlyphTags = enabled;
    }

    void hideScoreboardNumbers(boolean enabled) {
        hideScoreboardNumbers = enabled;
    }

    private Object transform(Object packet) {
        try {
            if (packet instanceof ClientboundBundlePacket bundle) return transformBundle(bundle);
            if (packet instanceof ClientboundOpenScreenPacket openScreen) return transformOpenScreen(openScreen);
            if (packet instanceof ClientboundSetTitleTextPacket title) return transformTitle(title);
            if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) return transformSubtitle(subtitle);
            if (packet instanceof ClientboundSetActionBarTextPacket actionBar) return transformActionBar(actionBar);
            if (packet instanceof ClientboundSystemChatPacket systemChat) return transformSystemChat(systemChat);
            if (packet instanceof ClientboundDisguisedChatPacket disguisedChat) return transformDisguisedChat(disguisedChat);
            if (packet instanceof ClientboundTabListPacket tabList) return transformTabList(tabList);
            if (packet instanceof ClientboundPlayerInfoUpdatePacket playerInfo) return transformPlayerInfo(playerInfo);
            if (packet instanceof ClientboundBossEventPacket bossBar) return transformBossBar(bossBar);
            if (packet instanceof ClientboundSetScorePacket score) return transformScore(score);
            if (packet instanceof ClientboundSetPlayerTeamPacket team) return transformTeam(team);
            if (packet instanceof ClientboundMerchantOffersPacket merchantOffers) return transformMerchantOffers(merchantOffers);
            if (packet instanceof ClientboundUpdateAdvancementsPacket advancements) return transformAdvancements(advancements);
            if (packet instanceof ClientboundSetEntityDataPacket entityData) return transformEntityData(entityData);
            if (packet instanceof ClientboundContainerSetContentPacket contents) return transformContainerContents(contents);
            if (packet instanceof ClientboundContainerSetSlotPacket slot) return transformContainerSlot(slot);
            if (packet instanceof ClientboundSetEquipmentPacket equipment) return transformEquipment(equipment);
            if (packet instanceof ClientboundSetPlayerInventoryPacket inventory) return transformPlayerInventory(inventory);
            if (packet instanceof ClientboundSetObjectivePacket objective) return transformObjective(objective);
        } catch (Throwable exception) {
            if (Settings.DEBUG.toBool())
                Logs.logWarning("Failed to transform outgoing packet " + packet.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        return packet;
    }

    @SuppressWarnings("unchecked")
    private ClientboundBundlePacket transformBundle(ClientboundBundlePacket bundle) {
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        boolean changed = false;
        for (Packet<? super ClientGamePacketListener> subPacket : bundle.subPackets()) {
            Object transformed = transform(subPacket);
            if (transformed != subPacket) changed = true;
            packets.add(transformed instanceof Packet<?> packet
                    ? (Packet<? super ClientGamePacketListener>) packet
                    : subPacket);
        }
        return changed ? new ClientboundBundlePacket(packets) : bundle;
    }

    private ClientboundOpenScreenPacket transformOpenScreen(ClientboundOpenScreenPacket packet) {
        boolean formatMiniMessage = formatInventoryTitles && Settings.FORMAT_INVENTORY_TITLES.toBool();
        boolean glyphTags = formatGlyphTags && Settings.FORMAT_GLYPH_TAGS.toBool();
        if (!formatMiniMessage && !glyphTags) return packet;
        return new ClientboundOpenScreenPacket(packet.getContainerId(), packet.getType(),
                transform(packet.getTitle(), formatMiniMessage, glyphTags));
    }

    private ClientboundSetTitleTextPacket transformTitle(ClientboundSetTitleTextPacket packet) {
        boolean formatMiniMessage = formatTitles && Settings.FORMAT_TITLES.toBool();
        boolean glyphTags = formatGlyphTags && Settings.FORMAT_GLYPH_TAGS.toBool();
        if (!formatMiniMessage && !glyphTags) return packet;
        return new ClientboundSetTitleTextPacket(transform(packet.text(), formatMiniMessage, glyphTags));
    }

    private ClientboundSetSubtitleTextPacket transformSubtitle(ClientboundSetSubtitleTextPacket packet) {
        boolean formatMiniMessage = formatTitles && Settings.FORMAT_SUBTITLES.toBool();
        boolean glyphTags = formatGlyphTags && Settings.FORMAT_GLYPH_TAGS.toBool();
        if (!formatMiniMessage && !glyphTags) return packet;
        return new ClientboundSetSubtitleTextPacket(transform(packet.text(), formatMiniMessage, glyphTags));
    }

    private ClientboundSetActionBarTextPacket transformActionBar(ClientboundSetActionBarTextPacket packet) {
        boolean formatMiniMessage = formatTitles && Settings.FORMAT_ACTION_BAR.toBool();
        boolean glyphTags = formatGlyphTags && Settings.FORMAT_GLYPH_TAGS.toBool();
        if (!formatMiniMessage && !glyphTags) return packet;
        return new ClientboundSetActionBarTextPacket(transform(packet.text(), formatMiniMessage, glyphTags));
    }

    private ClientboundSystemChatPacket transformSystemChat(ClientboundSystemChatPacket packet) {
        boolean enabled = packet.overlay() ? Settings.FORMAT_ACTION_BAR.toBool() : Settings.FORMAT_CHAT.toBool();
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool() || !enabled) return packet;
        return new ClientboundSystemChatPacket(transform(packet.content(), false, true), packet.overlay());
    }

    private ClientboundDisguisedChatPacket transformDisguisedChat(ClientboundDisguisedChatPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool() || !Settings.FORMAT_CHAT.toBool()) return packet;
        return new ClientboundDisguisedChatPacket(transform(packet.message(), false, true), packet.chatType());
    }

    private ClientboundTabListPacket transformTabList(ClientboundTabListPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        return new ClientboundTabListPacket(transform(packet.header(), false, true),
                transform(packet.footer(), false, true));
    }

    private ClientboundPlayerInfoUpdatePacket transformPlayerInfo(ClientboundPlayerInfoUpdatePacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = packet.entries().stream().map(entry -> {
            Component displayName = entry.displayName();
            if (displayName == null) return entry;
            return new ClientboundPlayerInfoUpdatePacket.Entry(entry.profileId(), entry.profile(), entry.listed(),
                    entry.latency(), entry.gameMode(), transform(displayName, false, true), entry.showHat(),
                    entry.listOrder(), entry.chatSession());
        }).toList();
        return new ClientboundPlayerInfoUpdatePacket(packet.actions(), entries);
    }

    private ClientboundBossEventPacket transformBossBar(ClientboundBossEventPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        ClientboundBossEventPacket[] replacement = new ClientboundBossEventPacket[1];
        packet.dispatch(new ClientboundBossEventPacket.Handler() {
            @Override
            public void add(java.util.UUID id, Component name, float progress,
                            net.minecraft.world.BossEvent.BossBarColor color,
                            net.minecraft.world.BossEvent.BossBarOverlay overlay,
                            boolean darkenScreen, boolean playBossMusic, boolean createWorldFog) {
                var bossEvent = new net.minecraft.world.BossEvent(id, transform(name, false, true), color, overlay) {
                };
                bossEvent.setProgress(progress);
                bossEvent.setDarkenScreen(darkenScreen);
                bossEvent.setPlayBossMusic(playBossMusic);
                bossEvent.setCreateWorldFog(createWorldFog);
                replacement[0] = ClientboundBossEventPacket.createAddPacket(bossEvent);
            }

            @Override
            public void updateName(java.util.UUID id, Component name) {
                var bossEvent = new net.minecraft.world.BossEvent(id, transform(name, false, true),
                        net.minecraft.world.BossEvent.BossBarColor.WHITE,
                        net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS) {
                };
                replacement[0] = ClientboundBossEventPacket.createUpdateNamePacket(bossEvent);
            }
        });
        return replacement[0] == null ? packet : replacement[0];
    }

    private ClientboundSetScorePacket transformScore(ClientboundSetScorePacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool() || packet.display().isEmpty()) return packet;
        return new ClientboundSetScorePacket(packet.owner(), packet.objectiveName(), packet.score(),
                packet.display().map(display -> transform(display, false, true)), packet.numberFormat());
    }

    private ClientboundSetPlayerTeamPacket transformTeam(ClientboundSetPlayerTeamPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        var parameters = packet.getParameters().orElse(null);
        if (parameters == null) return packet;

        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam team = new PlayerTeam(scoreboard, packet.getName());
        team.setDisplayName(transform(parameters.getDisplayName(), false, true));
        team.setPlayerPrefix(transform(parameters.getPlayerPrefix(), false, true));
        team.setPlayerSuffix(transform(parameters.getPlayerSuffix(), false, true));
        team.setColor(parameters.getColor());
        team.setNameTagVisibility(parameters.getNametagVisibility());
        team.setCollisionRule(parameters.getCollisionRule());
        team.unpackOptions(parameters.getOptions());
        packet.getPlayers().forEach(player -> scoreboard.addPlayerToTeam(player, team));
        return ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team,
                packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD);
    }

    private ClientboundMerchantOffersPacket transformMerchantOffers(ClientboundMerchantOffersPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        MerchantOffers offers = packet.getOffers().copy();
        for (MerchantOffer offer : offers) {
            offer.baseCostA = transformItemCost(offer.baseCostA);
            offer.costB = offer.costB.map(PacketHandler::transformItemCost);
            rewriteItemComponents(offer.result);
        }
        return new ClientboundMerchantOffersPacket(packet.getContainerId(), offers, packet.getVillagerLevel(),
                packet.getVillagerXp(), packet.showProgress(), packet.canRestock());
    }

    private static ItemCost transformItemCost(ItemCost itemCost) {
        return new ItemCost(itemCost.item(), itemCost.count(), itemCost.components(),
                transformItem(itemCost.itemStack()));
    }

    private ClientboundUpdateAdvancementsPacket transformAdvancements(ClientboundUpdateAdvancementsPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        List<AdvancementHolder> added = packet.getAdded().stream().map(holder -> {
            Advancement advancement = holder.value();
            var display = advancement.display().map(info -> {
                DisplayInfo transformed = new DisplayInfo(
                        net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(transformItem(info.getIcon().create())),
                        transform(info.getTitle(), false, true), transform(info.getDescription(), false, true),
                        info.getBackground(), info.getType(), info.shouldShowToast(), info.shouldAnnounceChat(),
                        info.isHidden());
                transformed.setLocation(info.getX(), info.getY());
                return transformed;
            });
            Advancement transformed = new Advancement(advancement.parent(), display, advancement.rewards(),
                    advancement.criteria(), advancement.requirements(), advancement.sendsTelemetryEvent(),
                    advancement.name());
            return new AdvancementHolder(holder.id(), transformed);
        }).toList();
        return new ClientboundUpdateAdvancementsPacket(packet.shouldReset(), added, packet.getRemoved(),
                packet.getProgress(), packet.shouldShowAdvancements());
    }

    private ClientboundSetEntityDataPacket transformEntityData(ClientboundSetEntityDataPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        List<SynchedEntityData.DataValue<?>> values = packet.packedItems().stream()
                .map(PacketHandler::transformEntityDataValue).toList();
        return new ClientboundSetEntityDataPacket(packet.id(), values);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SynchedEntityData.DataValue<?> transformEntityDataValue(SynchedEntityData.DataValue<?> value) {
        Object raw = value.value();
        Object transformed = raw;
        if (raw instanceof Component component) {
            transformed = transform(component, false, true);
        } else if (raw instanceof Optional<?> optional && optional.orElse(null) instanceof Component component) {
            transformed = optional.map(ignored -> transform(component, false, true));
        } else if (raw instanceof ItemStack item) {
            transformed = transformItem(item);
        }
        return transformed == raw ? value : new SynchedEntityData.DataValue(value.id(), value.serializer(), transformed);
    }

    private ClientboundContainerSetContentPacket transformContainerContents(ClientboundContainerSetContentPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        return new ClientboundContainerSetContentPacket(packet.containerId(), packet.stateId(),
                packet.items().stream().map(PacketHandler::transformItem).toList(), transformItem(packet.carriedItem()));
    }

    private ClientboundContainerSetSlotPacket transformContainerSlot(ClientboundContainerSetSlotPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        return new ClientboundContainerSetSlotPacket(packet.getContainerId(), packet.getStateId(), packet.getSlot(),
                transformItem(packet.getItem()));
    }

    private ClientboundSetEquipmentPacket transformEquipment(ClientboundSetEquipmentPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot, ItemStack>> slots = packet.getSlots()
                .stream().map(slot -> com.mojang.datafixers.util.Pair.of(slot.getFirst(), transformItem(slot.getSecond())))
                .toList();
        return new ClientboundSetEquipmentPacket(packet.getEntity(), slots);
    }

    private ClientboundSetPlayerInventoryPacket transformPlayerInventory(ClientboundSetPlayerInventoryPacket packet) {
        if (!formatGlyphTags || !Settings.FORMAT_GLYPH_TAGS.toBool()) return packet;
        return new ClientboundSetPlayerInventoryPacket(packet.slot(), transformItem(packet.contents()));
    }

    private ClientboundSetObjectivePacket transformObjective(ClientboundSetObjectivePacket packet) {
        boolean hideNumbers = hideScoreboardNumbers && packet.getMethod() != ClientboundSetObjectivePacket.METHOD_REMOVE;
        boolean glyphTags = formatGlyphTags && Settings.FORMAT_GLYPH_TAGS.toBool()
                && packet.getMethod() != ClientboundSetObjectivePacket.METHOD_REMOVE;
        if (!hideNumbers && !glyphTags) return packet;
        Objective nativeObjective = new Objective(new Scoreboard(), packet.getObjectiveName(), ObjectiveCriteria.DUMMY,
                glyphTags ? transform(packet.getDisplayName(), false, true) : packet.getDisplayName(),
                packet.getRenderType(), false,
                hideNumbers ? BlankFormat.INSTANCE : packet.getNumberFormat().orElse(BlankFormat.INSTANCE));
        return new ClientboundSetObjectivePacket(nativeObjective, packet.getMethod());
    }

    private static Component transform(Component component, boolean formatMiniMessage, boolean resolveGlyphTags) {
        return PaperAdventure.asVanilla(PacketHelpers.translateOutgoingText(PaperAdventure.asAdventure(component),
                formatMiniMessage, resolveGlyphTags));
    }

    private static ItemStack transformItem(ItemStack item) {
        if (item == null || item.isEmpty()) return item;
        ItemStack transformed = item.copy();
        rewriteItemComponents(transformed);
        return transformed;
    }

    private static void rewriteItemComponents(ItemStack transformed) {
        if (transformed == null || transformed.isEmpty()) return;
        if (transformed.has(DataComponents.CUSTOM_NAME))
            transformed.set(DataComponents.CUSTOM_NAME, transform(transformed.get(DataComponents.CUSTOM_NAME), false, true));
        if (transformed.has(DataComponents.ITEM_NAME))
            transformed.set(DataComponents.ITEM_NAME, transform(transformed.get(DataComponents.ITEM_NAME), false, true));
        ItemLore lore = transformed.get(DataComponents.LORE);
        if (lore != null)
            transformed.set(DataComponents.LORE, new ItemLore(
                    lore.lines().stream().map(line -> transform(line, false, true)).toList(),
                    lore.styledLines().stream().map(line -> transform(line, false, true)).toList()));

        if (Settings.FORMAT_BOOKS.toBool()) {
            WrittenBookContent writtenBook = transformed.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (writtenBook != null)
                transformed.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                        writtenBook.title().map(PacketHandler::transformBookTitle), writtenBook.author(),
                        writtenBook.generation(), writtenBook.pages().stream()
                        .map(page -> page.map(value -> transform(value, false, true))).toList(), writtenBook.resolved()));
            WritableBookContent writableBook = transformed.get(DataComponents.WRITABLE_BOOK_CONTENT);
            if (writableBook != null)
                transformed.set(DataComponents.WRITABLE_BOOK_CONTENT, writableBook.withReplacedPages(
                        writableBook.pages().stream().map(page -> page.map(PacketHandler::transformBookPage)).toList()));
        }
    }

    private static String transformBookPage(String page) {
        try {
            net.kyori.adventure.text.Component component =
                    io.th0rgal.oraxen.utils.AdventureUtils.GSON_SERIALIZER.deserialize(page);
            return io.th0rgal.oraxen.utils.AdventureUtils.GSON_SERIALIZER.serialize(
                    io.th0rgal.oraxen.utils.GlyphTagRewriter.rewrite(component));
        } catch (RuntimeException ignored) {
            net.kyori.adventure.text.Component component = io.th0rgal.oraxen.utils.GlyphTagRewriter.rewrite(
                    PaperAdventure.asAdventure(Component.literal(page)));
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
                    component);
        }
    }

    private static String transformBookTitle(String title) {
        return io.th0rgal.oraxen.utils.AdventureUtils.PLAIN_TEXT.serialize(
                io.th0rgal.oraxen.utils.GlyphTagRewriter.rewrite(
                        net.kyori.adventure.text.Component.text(title)));
    }
}
