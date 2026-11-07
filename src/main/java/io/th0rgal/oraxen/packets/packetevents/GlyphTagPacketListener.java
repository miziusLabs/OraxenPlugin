package io.th0rgal.oraxen.packets.packetevents;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.advancements.AdvancementHolder;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemLore;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WritableBookContent;
import com.github.retrooper.packetevents.protocol.component.builtin.item.WrittenBookContent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.recipe.data.MerchantOffer;
import com.github.retrooper.packetevents.util.Filterable;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisconnect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMerchantOffers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerListHeaderAndFooter;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerServerData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAdvancements;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.utils.AdventureUtils;
import io.th0rgal.oraxen.utils.GlyphTagRewriter;
import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Optional;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.BOSS_BAR;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.DISGUISED_CHAT;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.DISCONNECT;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_EQUIPMENT;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ENTITY_METADATA;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.MERCHANT_OFFERS;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.PLAYER_INFO_UPDATE;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.PLAYER_LIST_HEADER_AND_FOOTER;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SCOREBOARD_OBJECTIVE;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SERVER_DATA;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SET_PLAYER_INVENTORY;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SET_SLOT;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SYSTEM_CHAT_MESSAGE;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.TEAMS;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.UPDATE_ADVANCEMENTS;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.UPDATE_SCORE;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.WINDOW_ITEMS;

public class GlyphTagPacketListener implements PacketListener {

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!Settings.FORMAT_GLYPH_TAGS.toBool()) return;

        if (event.getPacketType() == SYSTEM_CHAT_MESSAGE) {
            var wrapper = new WrapperPlayServerSystemChatMessage(event);
            if (wrapper.isOverlay()) {
                if (Settings.FORMAT_ACTION_BAR.toBool()) wrapper.setMessage(rewrite(wrapper.getMessage()));
            } else if (Settings.FORMAT_CHAT.toBool()) {
                wrapper.setMessage(rewrite(wrapper.getMessage()));
            }
        } else if (event.getPacketType() == DISGUISED_CHAT && Settings.FORMAT_CHAT.toBool()) {
            var wrapper = new WrapperPlayServerDisguisedChat(event);
            wrapper.setMessage(rewrite(wrapper.getMessage()));
        } else if (event.getPacketType() == PLAYER_LIST_HEADER_AND_FOOTER) {
            var wrapper = new WrapperPlayServerPlayerListHeaderAndFooter(event);
            wrapper.setHeader(rewrite(wrapper.getHeader()));
            wrapper.setFooter(rewrite(wrapper.getFooter()));
        } else if (event.getPacketType() == PLAYER_INFO_UPDATE) {
            var wrapper = new WrapperPlayServerPlayerInfoUpdate(event);
            for (var entry : wrapper.getEntries()) {
                if (entry.getDisplayName() != null) entry.setDisplayName(rewrite(entry.getDisplayName()));
            }
            wrapper.setEntries(wrapper.getEntries());
        } else if (event.getPacketType() == BOSS_BAR) {
            var wrapper = new WrapperPlayServerBossBar(event);
            if (wrapper.getTitle() != null) wrapper.setTitle(rewrite(wrapper.getTitle()));
        } else if (event.getPacketType() == SCOREBOARD_OBJECTIVE) {
            var wrapper = new WrapperPlayServerScoreboardObjective(event);
            if (wrapper.getMode() != WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE
                    && wrapper.getDisplayName() != null)
                wrapper.setDisplayName(rewrite(wrapper.getDisplayName()));
        } else if (event.getPacketType() == UPDATE_SCORE) {
            var wrapper = new WrapperPlayServerUpdateScore(event);
            if (wrapper.getEntityDisplayName() != null)
                wrapper.setEntityDisplayName(rewrite(wrapper.getEntityDisplayName()));
        } else if (event.getPacketType() == TEAMS) {
            rewriteTeam(event);
        } else if (event.getPacketType() == ENTITY_METADATA) {
            rewriteEntityMetadata(event);
        } else if (event.getPacketType() == WINDOW_ITEMS) {
            var wrapper = new WrapperPlayServerWindowItems(event);
            wrapper.getItems().forEach(GlyphTagPacketListener::rewriteItem);
            wrapper.getCarriedItem().ifPresent(GlyphTagPacketListener::rewriteItem);
            wrapper.setItems(wrapper.getItems());
        } else if (event.getPacketType() == SET_SLOT) {
            var wrapper = new WrapperPlayServerSetSlot(event);
            rewriteItem(wrapper.getItem());
            wrapper.setItem(wrapper.getItem());
        } else if (event.getPacketType() == SET_PLAYER_INVENTORY) {
            var wrapper = new WrapperPlayServerSetPlayerInventory(event);
            rewriteItem(wrapper.getStack());
            wrapper.setStack(wrapper.getStack());
        } else if (event.getPacketType() == ENTITY_EQUIPMENT) {
            var wrapper = new WrapperPlayServerEntityEquipment(event);
            for (Equipment equipment : wrapper.getEquipment()) rewriteItem(equipment.getItem());
            wrapper.setEquipment(wrapper.getEquipment());
        } else if (event.getPacketType() == MERCHANT_OFFERS) {
            var wrapper = new WrapperPlayServerMerchantOffers(event);
            for (MerchantOffer offer : wrapper.getMerchantOffers()) {
                rewriteItem(offer.getFirstInputItem());
                if (offer.getSecondInputItem() != null) rewriteItem(offer.getSecondInputItem());
                rewriteItem(offer.getOutputItem());
            }
            wrapper.setMerchantOffers(wrapper.getMerchantOffers());
        } else if (event.getPacketType() == UPDATE_ADVANCEMENTS) {
            rewriteAdvancements(event);
        } else if (event.getPacketType() == SERVER_DATA) {
            var wrapper = new WrapperPlayServerServerData(event);
            if (wrapper.getMOTD() != null) wrapper.setMOTD(rewrite(wrapper.getMOTD()));
        } else if (event.getPacketType() == DISCONNECT) {
            var wrapper = new WrapperPlayServerDisconnect(event);
            if (wrapper.getReason() != null) wrapper.setReason(rewrite(wrapper.getReason()));
        }
    }

    private static void rewriteTeam(PacketSendEvent event) {
        var wrapper = new WrapperPlayServerTeams(event);
        wrapper.getTeamInfo().ifPresent(info -> {
            if (info.getDisplayName() != null) info.setDisplayName(rewrite(info.getDisplayName()));
            if (info.getPrefix() != null) info.setPrefix(rewrite(info.getPrefix()));
            if (info.getSuffix() != null) info.setSuffix(rewrite(info.getSuffix()));
            wrapper.setTeamInfo(info);
        });
    }

    private static void rewriteEntityMetadata(PacketSendEvent event) {
        var wrapper = new WrapperPlayServerEntityMetadata(event);
        for (EntityData<?> data : wrapper.getEntityMetadata()) rewriteEntityData(data);
        wrapper.setEntityMetadata(wrapper.getEntityMetadata());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void rewriteEntityData(EntityData<?> data) {
        if (data.getValue() instanceof Component component)
            ((EntityData) data).setValue(rewrite(component));
    }

    private static void rewriteAdvancements(PacketSendEvent event) {
        var wrapper = new WrapperPlayServerUpdateAdvancements(event);
        for (AdvancementHolder holder : wrapper.getAddedAdvancements()) {
            var display = holder.getAdvancement().getDisplay();
            if (display == null) continue;
            display.setTitle(rewrite(display.getTitle()));
            display.setDescription(rewrite(display.getDescription()));
            rewriteItem(display.getIcon());
        }
        wrapper.setAddedAdvancements(wrapper.getAddedAdvancements());
    }

    private static Component rewrite(Component component) {
        return GlyphTagRewriter.rewrite(component);
    }

    private static void rewriteItem(ItemStack item) {
        if (item == null) return;

        item.getComponent(ComponentTypes.CUSTOM_NAME).ifPresent(name ->
                item.setComponent(ComponentTypes.CUSTOM_NAME, rewrite(name)));
        item.getComponent(ComponentTypes.ITEM_NAME).ifPresent(name ->
                item.setComponent(ComponentTypes.ITEM_NAME, rewrite(name)));
        item.getComponent(ComponentTypes.LORE).ifPresent(lore ->
                lore.setLines(lore.getLines().stream().map(GlyphTagPacketListener::rewrite).toList()));

        item.getComponent(ComponentTypes.CONTAINER).ifPresent(container ->
                container.getItems().forEach(GlyphTagPacketListener::rewriteItem));
        item.getComponent(ComponentTypes.BUNDLE_CONTENTS).ifPresent(bundle ->
                bundle.getItems().forEach(GlyphTagPacketListener::rewriteItem));

        if (!Settings.FORMAT_BOOKS.toBool()) return;
        item.getComponent(ComponentTypes.WRITTEN_BOOK_CONTENT).ifPresent(GlyphTagPacketListener::rewriteWrittenBook);
        item.getComponent(ComponentTypes.WRITABLE_BOOK_CONTENT).ifPresent(GlyphTagPacketListener::rewriteWritableBook);
    }

    private static void rewriteWrittenBook(WrittenBookContent book) {
        if (book.getTitle() != null) {
            Filterable<String> title = book.getTitle();
            book.setTitle(new Filterable<>(rewriteBookTitle(title.getRaw()),
                    title.getFiltered() == null ? Optional.empty() : Optional.of(rewriteBookTitle(title.getFiltered()))));
        }
        book.setPages(book.getPages().stream().map(page -> new Filterable<>(rewrite(page.getRaw()),
                page.getFiltered() == null ? Optional.empty() : Optional.of(rewrite(page.getFiltered())))).toList());
    }

    private static void rewriteWritableBook(WritableBookContent book) {
        book.setPages(book.getPages().stream().map(page -> new Filterable<>(rewriteBookString(page.getRaw()),
                page.getFiltered() == null ? Optional.empty() : Optional.of(rewriteBookString(page.getFiltered())))).toList());
    }

    private static String rewriteBookString(String input) {
        try {
            Component component = AdventureUtils.GSON_SERIALIZER.deserialize(input);
            return AdventureUtils.GSON_SERIALIZER.serialize(rewrite(component));
        } catch (RuntimeException ignored) {
            Component component = rewrite(Component.text(input));
            return AdventureUtils.PLAIN_TEXT.serialize(component);
        }
    }

    private static String rewriteBookTitle(String title) {
        return AdventureUtils.PLAIN_TEXT.serialize(rewrite(Component.text(title)));
    }
}
