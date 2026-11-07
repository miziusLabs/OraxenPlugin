package io.th0rgal.oraxen.utils;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.configs.ConfigsManager;
import io.th0rgal.oraxen.fonts.FontManager;
import io.th0rgal.oraxen.glyphs.AnimatedGlyph;
import io.th0rgal.oraxen.glyphs.Glyph;
import io.th0rgal.oraxen.glyphs.GlyphAppearance;
import io.th0rgal.oraxen.glyphs.ReferenceGlyph;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.bukkit.configuration.file.YamlConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlyphTagRewriterTest {

    @Test
    void rewritesLiteralLongAndShortTagsAndKeepsSurroundingStyle() {
        Glyph glyph = regularGlyph("A");
        try (var plugin = configuredPlugin(glyph, null, null)) {
            Component parsedGlyph = AdventureUtils.MINI_MESSAGE.deserialize("<glyph:heart>");
            assertEquals("A", AdventureUtils.PLAIN_TEXT.serialize(parsedGlyph));
            Component parsedOption = AdventureUtils.MINI_MESSAGE.deserialize("<glyph:heart:colorable>");
            assertEquals("A", AdventureUtils.PLAIN_TEXT.serialize(parsedOption));
            Component input = MiniMessage.miniMessage().deserialize(
                    "<red>before <glyph:heart:colorable> and <g:heart:colorable> after</red>");

            Component result = GlyphTagRewriter.rewrite(input);

            assertEquals("before A and A after", AdventureUtils.PLAIN_TEXT.serialize(result));
            assertEquals(NamedTextColor.RED, result.style().color());
        }
    }

    @Test
    void usesTheSharedResolverForAnimatedAndReferenceGlyphs() {
        Glyph regular = regularGlyph("R");
        AnimatedGlyph animated = mock(AnimatedGlyph.class);
        when(animated.hasPermission(null)).thenReturn(true);
        when(animated.getGlyphComponent()).thenReturn(Component.text("ANIM"));
        ReferenceGlyph reference = mock(ReferenceGlyph.class);
        when(reference.hasPermission(null)).thenReturn(true);
        when(reference.isResolved()).thenReturn(true);
        when(reference.getCharacters()).thenReturn("REF");
        when(reference.getFont()).thenReturn(Key.key("test", "glyphs"));
        when(reference.getAppearance()).thenReturn(new GlyphAppearance(Key.key("test", "glyphs"), null));

        try (var plugin = configuredPlugin(regular, animated, reference)) {
            Component input = MiniMessage.miniMessage().deserialize(
                    "<glyph:loading> <glyph:alias>");

            Component result = GlyphTagRewriter.rewrite(input);

            assertEquals("ANIM REF", AdventureUtils.PLAIN_TEXT.serialize(result));
        }
    }

    @Test
    void passesSupportedOptionsAndLeavesUnknownOrMalformedTagsLiteral() {
        Glyph glyph = regularGlyph("AB");
        try (var plugin = configuredPlugin(glyph, null, null)) {
            Component input = MiniMessage.miniMessage().deserialize(
                    "<red><glyph:heart:colorable:shadow:#112233:2> | <glyph:missing> | <glyph:> | <image:asset></red>");

            Component result = GlyphTagRewriter.rewrite(input);

            assertEquals("B | <glyph:missing> | <glyph:> | <image:asset>",
                    AdventureUtils.PLAIN_TEXT.serialize(result));
            assertEquals(NamedTextColor.RED, result.style().color());
            verify(glyph, atLeastOnce()).hasPermission(null);
        }
    }

    @Test
    void returnsTheOriginalComponentWhenGlyphReplacementIsDisabled() {
        try (var plugin = configuredPlugin(regularGlyph("A"), null, null)) {
            Component input = MiniMessage.miniMessage().deserialize("before <glyph:heart> after");

            Component result = PacketHelpers.translateOutgoingText(input, false, false);

            assertSame(input, result);
            assertEquals("before <glyph:heart> after", AdventureUtils.PLAIN_TEXT.serialize(result));
        }
    }

    @Test
    void rewritesLiteralGlyphsInJsonPacketComponents() {
        try (var plugin = configuredPlugin(regularGlyph("A"), null, null)) {
            Component input = MiniMessage.miniMessage().deserialize("<red>before <glyph:heart> after</red>");
            String json = AdventureUtils.GSON_SERIALIZER.serialize(input);

            Component result = AdventureUtils.GSON_SERIALIZER.deserialize(
                    PacketHelpers.translateJson(json, false, true));

            assertEquals("before A after", AdventureUtils.PLAIN_TEXT.serialize(result));
            assertEquals(NamedTextColor.RED, result.style().color());
        }
    }

    private static Glyph regularGlyph(String characters) {
        Glyph glyph = mock(Glyph.class);
        when(glyph.getFormattedUnicodes()).thenReturn(characters);
        when(glyph.getAllChars()).thenReturn(characters.toCharArray());
        when(glyph.getFont()).thenReturn(Key.key("test", "glyphs"));
        when(glyph.getAppearance()).thenReturn(new GlyphAppearance(Key.key("test", "glyphs"), null));
        when(glyph.hasPermission(null)).thenReturn(true);
        when(glyph.getGlyphTag()).thenReturn("<glyph:heart>");
        return glyph;
    }

    private static MockedStatic<OraxenPlugin> configuredPlugin(Glyph glyph, AnimatedGlyph animated,
                                                                 ReferenceGlyph reference) {
        OraxenPlugin plugin = mock(OraxenPlugin.class);
        FontManager fontManager = mock(FontManager.class);
        ConfigsManager configsManager = mock(ConfigsManager.class);
        YamlConfiguration language = new YamlConfiguration();
        language.set("general.prefix", "");
        when(plugin.getFontManager()).thenReturn(fontManager);
        when(plugin.getConfigsManager()).thenReturn(configsManager);
        when(configsManager.getLanguage()).thenReturn(language);
        when(fontManager.getGlyphFromID("heart")).thenReturn(glyph);
        when(fontManager.getGlyphFromName("heart")).thenReturn(glyph);
        if (animated != null) when(fontManager.getAnimatedGlyphFromID("loading")).thenReturn(animated);
        if (reference != null) when(fontManager.getReferenceGlyphFromID("alias")).thenReturn(reference);

        MockedStatic<OraxenPlugin> mockedPlugin = mockStatic(OraxenPlugin.class);
        mockedPlugin.when(OraxenPlugin::get).thenReturn(plugin);
        return mockedPlugin;
    }
}
