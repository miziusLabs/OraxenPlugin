package io.th0rgal.oraxen.packets.packetevents;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.utils.PacketHelpers;

import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.ACTION_BAR;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SET_TITLE_SUBTITLE;
import static com.github.retrooper.packetevents.protocol.packettype.PacketType.Play.Server.SET_TITLE_TEXT;

public class TitlePacketListener implements PacketListener {
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if(event.getPacketType() == SET_TITLE_TEXT
                && (Settings.FORMAT_TITLES.toBool() || Settings.FORMAT_GLYPH_TAGS.toBool())) {
            var wrapper = new WrapperPlayServerJsonComponent(event);
            wrapper.setComponentJson(PacketHelpers.translateJson(wrapper.getComponentJson(),
                    Settings.FORMAT_TITLES.toBool(), Settings.FORMAT_GLYPH_TAGS.toBool()));
        } else if(event.getPacketType() == SET_TITLE_SUBTITLE
                && (Settings.FORMAT_SUBTITLES.toBool() || Settings.FORMAT_GLYPH_TAGS.toBool())) {
            var wrapper = new WrapperPlayServerJsonComponent(event);
            wrapper.setComponentJson(PacketHelpers.translateJson(wrapper.getComponentJson(),
                    Settings.FORMAT_SUBTITLES.toBool(), Settings.FORMAT_GLYPH_TAGS.toBool()));
        } else if(event.getPacketType() == ACTION_BAR
                && (Settings.FORMAT_ACTION_BAR.toBool() || Settings.FORMAT_GLYPH_TAGS.toBool())) {
            var wrapper = new WrapperPlayServerJsonComponent(event);
            wrapper.setComponentJson(PacketHelpers.translateJson(wrapper.getComponentJson(),
                    Settings.FORMAT_ACTION_BAR.toBool(), Settings.FORMAT_GLYPH_TAGS.toBool()));
        }
    }
}
