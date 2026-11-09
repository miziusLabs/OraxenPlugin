package io.th0rgal.oraxen.utils;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.fonts.FontManager;
import io.th0rgal.oraxen.glyphs.AnimatedGlyph;
import io.th0rgal.oraxen.glyphs.Glyph;
import io.th0rgal.oraxen.glyphs.ReferenceGlyph;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.regex.Pattern;

/** Replaces literal Oraxen glyph tags in already-created Adventure components. */
public final class GlyphTagRewriter {

    // Keep colon-separated options in one payload to avoid repeated ambiguous matcher groups.
    private static final Pattern GLYPH_TAG = Pattern.compile(
            "<(?:glyph|g):([^\\s<>:]+)(?::[^\\s<>]+)?(?:\\s[^<>]*)?>", Pattern.CASE_INSENSITIVE);

    private GlyphTagRewriter() {
    }

    @Nullable
    public static Component rewrite(@Nullable Component component) {
        if (component == null) return null;
        return rewrite(component, GlyphTagRewriter::resolveTag);
    }

    public static Component rewrite(@Nullable Component component, Player player) {
        if (component == null) return null;
        return rewrite(component, tag -> resolveTag(tag, player));
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
        return resolveTag(tag, null);
    }

    private static Component resolveTag(String tag, @Nullable Player player) {
        var matcher = GLYPH_TAG.matcher(tag);
        if (!matcher.matches()) return Component.text(tag);

        FontManager fontManager = OraxenPlugin.get().getFontManager();
        if (fontManager == null) return Component.text(tag);

        String glyphId = matcher.group(1);
        AnimatedGlyph animatedGlyph = fontManager.getAnimatedGlyphFromID(glyphId);
        if (animatedGlyph != null) {
            if (player != null && !animatedGlyph.hasPermission(player)) return Component.text(tag);
            return AdventureUtils.MINI_MESSAGE.deserialize(tag);
        }

        ReferenceGlyph referenceGlyph = fontManager.getReferenceGlyphFromID(glyphId);
        if (referenceGlyph != null) {
            if (player != null && !referenceGlyph.hasPermission(player)) return Component.text(tag);
            return AdventureUtils.MINI_MESSAGE.deserialize(tag);
        }

        Glyph glyph = fontManager.getGlyphFromID(glyphId);
        if (glyph == null) return Component.text(tag);
        if (player != null && !glyph.hasPermission(player)) return Component.text(tag);

        return AdventureUtils.MINI_MESSAGE.deserialize(tag);
    }
}
