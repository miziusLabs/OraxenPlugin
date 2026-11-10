package io.th0rgal.oraxen.utils;

import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.utils.logs.Logs;
import net.kyori.adventure.text.Component;

public class PacketHelpers {

    public static final String BACKSLASH_REMOVAL_REGEX = "\\\\(?!u)(?!n)(?!\")";

    // Serialize initial string from json to component, then parse to handle tags and serialize again to json string
    public static String readJson(String text) {
        return AdventureUtils.parseMiniMessageThroughLegacy(AdventureUtils.GSON_SERIALIZER.deserialize(text));
    }

    public static String toJson(String text) {
        return AdventureUtils.GSON_SERIALIZER.serialize(AdventureUtils.MINI_MESSAGE.deserialize(text)).replaceAll(BACKSLASH_REMOVAL_REGEX, "");
    }

    public static String translateJson(String json) {
        return translateJson(json, true, true);
    }

    public static String translateJson(String json, boolean formatMiniMessage, boolean resolveGlyphTags) {
        if (json == null) return null;
        try {
            Component component = AdventureUtils.GSON_SERIALIZER.deserialize(json);
            component = translateOutgoingText(component, formatMiniMessage, resolveGlyphTags);
            return AdventureUtils.GSON_SERIALIZER.serialize(component).replaceAll(BACKSLASH_REMOVAL_REGEX, "");
        } catch (RuntimeException exception) {
            if (Settings.DEBUG.toBool()) {
                Logs.logWarning("PacketHelpers.translateJson failed, returning original payload: " + exception.getMessage());
            }
            return json;
        }
    }

    public static Component translateTitle(Component component) {
        return translateOutgoingText(component, true, true);
    }

    public static Component translateOutgoingText(Component component, boolean formatMiniMessage, boolean resolveGlyphTags) {
        var miniMessage = AdventureUtils.MINI_MESSAGE_WITHOUT_GLYPHS;
        Component translated = component;
        if (formatMiniMessage) {
            translated = miniMessage.deserialize(
                    AdventureUtils.parseMiniMessageThroughLegacy(component, miniMessage)
                            .replaceAll(BACKSLASH_REMOVAL_REGEX, ""));
        }
        return resolveGlyphTags ? GlyphTagRewriter.rewrite(translated) : translated;
    }
}
