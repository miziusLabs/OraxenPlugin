package io.th0rgal.oraxen.utils;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.fonts.FontManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.regex.Pattern;

/** Replaces literal Oraxen glyph tags in already-created Adventure components. */
public final class GlyphTagRewriter {

    private static final Pattern GLYPH_TAG = Pattern.compile(
            "<(?:glyph|g):([^\\s<>:]+)(?::[^\\s<>]+)*(?:\\s+[^<>]*)?>", Pattern.CASE_INSENSITIVE);

    private GlyphTagRewriter() {
    }

    @Nullable
    public static Component rewrite(@Nullable Component component) {
        if (component == null) return null;
        return rewrite(component, GlyphTagRewriter::resolveTag);
    }

    static Component rewrite(Component component, Function<String, Component> tagResolver) {
        TextReplacementConfig config = TextReplacementConfig.builder()
                .match(GLYPH_TAG)
                .replacement((match, fallback) -> {
                    try {
                        return tagResolver.apply(match.group());
                    } catch (RuntimeException ignored) {
                        return fallback;
                    }
                })
                .replaceInsideHoverEvents(true)
                .build();
        return component.replaceText(config);
    }

    private static Component resolveTag(String tag) {
        var matcher = GLYPH_TAG.matcher(tag);
        if (!matcher.matches()) return Component.text(tag);

        FontManager fontManager = OraxenPlugin.get().getFontManager();
        if (fontManager == null) return Component.text(tag);

        String glyphId = matcher.group(1);
        if (fontManager.getGlyphFromID(glyphId) == null
                && fontManager.getAnimatedGlyphFromID(glyphId) == null
                && fontManager.getReferenceGlyphFromID(glyphId) == null)
            return Component.text(tag);

        return AdventureUtils.MINI_MESSAGE.deserialize(tag);
    }
}
