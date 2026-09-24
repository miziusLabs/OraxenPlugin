package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.fonts.Font;
import io.th0rgal.oraxen.fonts.FontManager;
import io.th0rgal.oraxen.glyphs.AnimatedGlyph;
import io.th0rgal.oraxen.glyphs.Glyph;
import io.th0rgal.oraxen.glyphs.ShiftProvider;
import io.th0rgal.oraxen.utils.VersionUtil;
import io.th0rgal.oraxen.utils.VirtualFile;
import io.th0rgal.oraxen.utils.logs.Logs;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Collection;

/** Generates fonts, animated glyph textures, and their shader variants. */
final class FontAssetGenerator {
    private final File packFolder;
    private final TextShaderGenerator textShaderGenerator;

    FontAssetGenerator(File packFolder, TextShaderGenerator textShaderGenerator) {
        this.packFolder = packFolder;
        this.textShaderGenerator = textShaderGenerator;
    }

    void generate(boolean multiVersionResolved) {
        FontManager fontManager = OraxenPlugin.get().getFontManager();
        if (!fontManager.autoGenerate)
            return;

        // Generate the main default font with glyphs
        final JsonObject output = new JsonObject();
        final JsonArray providers = new JsonArray();
        for (final Glyph glyph : fontManager.getGlyphs()) {
            if (glyph.hasBitmap()) continue;
            JsonObject glyphJson = glyph.toJson();
            if (glyphJson != null) providers.add(glyphJson);
        }
        for (FontManager.GlyphBitMap glyphBitMap : FontManager.glyphBitMaps.values()) {
            providers.add(glyphBitMap.toJson(fontManager));
        }
        for (final Font font : fontManager.getFonts()) {
            providers.add(font.toJson());
        }

        // Add shift provider to default font for backward compatibility.
        // This allows getShift() to work in plain strings (e.g., GUI titles)
        // without requiring the oraxen:shift font to be explicitly applied.
        ShiftProvider shiftProvider = fontManager.getShiftProvider();
        providers.add(shiftProvider.toProviderJson());

        output.add("providers", providers);
        ResourcePack.writeStringToVirtual("assets/minecraft/font", "default.json", output.toString());
        if (Settings.FIX_FORCE_UNICODE_GLYPHS.toBool())
            ResourcePack.writeStringToVirtual("assets/minecraft/font", "uniform.json", output.toString());

        // Generate the dedicated shift font (still useful for explicit font references)
        generateShiftFont(fontManager);

        // Process animated glyph fonts
        boolean hasAnimatedGlyphs = processAnimatedGlyphs(fontManager);

        // Generate text shaders when animated glyphs are present.
        // In multi-version mode the choice depends on the *server* version:
        //   - 1.21.4+ server: skip base shaders and only emit 1.21.4+ overlays so
        //     1.21.3- clients never receive #version 330+ GLSL.
        //   - 1.20-1.21.3 server: emit base shaders for the server's legacy format
        //     AND 1.21.4+ overlays. Without the base emission, legacy clients on a
        //     multi-version pack would silently lose animated glyph
        //     rendering (the base format is the legacy one in that case).
        boolean serverIs1214Plus = VersionUtil.atOrAbove("1.21.4");
        TextShaderGenerator.ShaderEmissionMode emissionMode;
        if (multiVersionResolved) {
            emissionMode = serverIs1214Plus
                    ? TextShaderGenerator.ShaderEmissionMode.OVERLAY_ONLY_1214_PLUS
                    : TextShaderGenerator.ShaderEmissionMode.BASE_PLUS_1214_OVERLAYS;
        } else {
            emissionMode = TextShaderGenerator.ShaderEmissionMode.BASE_ONLY;
        }
        textShaderGenerator.maybeGenerateTextShaders(hasAnimatedGlyphs, emissionMode);
    }

    /**
     * Generates the dedicated shift font file (assets/oraxen/font/shift.json).
     * Uses a space font provider for efficient pixel-based text shifting.
     */
    private void generateShiftFont(FontManager fontManager) {
        ShiftProvider shiftProvider = fontManager.getShiftProvider();
        JsonObject shiftFont = shiftProvider.generateFontFile();
        ResourcePack.writeStringToVirtual("assets/oraxen/font", "shift.json", shiftFont.toString());
        if (Settings.DEBUG.toBool()) Logs.logInfo("Generated shift font with space provider");
    }

    /**
     * Processes animated glyphs: validates sprite sheets and generates font files.
     */
    private boolean processAnimatedGlyphs(FontManager fontManager) {
        Collection<AnimatedGlyph> animatedGlyphs = fontManager.getAnimatedGlyphs();
        if (animatedGlyphs.isEmpty()) {
            return false;
        }

        // Note: Codepoint counter is reset in ConfigsManager.parseAllGlyphConfigs()
        // BEFORE animated glyphs are created, ensuring clean codepoint allocation on
        // reload.

        if (Settings.DEBUG.toBool()) {
            Logs.logInfo("Processing " + animatedGlyphs.size() + " animated glyphs...");
        }

        for (AnimatedGlyph animGlyph : animatedGlyphs) {
            processAnimatedGlyph(animGlyph);
        }
        return true;
    }

    /**
     * Processes a single animated glyph: validates sprite sheet and generates font.
     */
    private void processAnimatedGlyph(AnimatedGlyph animGlyph) {
        File textureFile = animGlyph.getTextureFile(packFolder.toPath());

        if (!textureFile.exists()) {
            Logs.logWarning("Sprite sheet not found for animated glyph '" + animGlyph.getName() + "': "
                    + textureFile.getPath());
            return;
        }

        try {
            BufferedImage image = ImageIO.read(textureFile);
            if (image == null) {
                Logs.logError("Failed to read sprite sheet for: " + animGlyph.getName());
                return;
            }

            BufferedImage sheetImage = prepareAnimationSpriteSheet(animGlyph, image);
            if (sheetImage == null) return;

            boolean generatedStrip = (sheetImage != image);
            String spriteSheetPath = writeSpriteSheetIfNeeded(animGlyph, sheetImage, generatedStrip);

            int frameCount = Math.max(1, animGlyph.getFrameCount());
            int sheetWidth = sheetImage.getWidth();
            int sheetHeight = sheetImage.getHeight();
            int frameWidthPx;
            int frameHeightPx;

            if (sheetWidth % frameCount == 0) {
                frameWidthPx = sheetWidth / frameCount;
                frameHeightPx = sheetHeight;
            } else if (sheetHeight % frameCount == 0) {
                frameWidthPx = sheetWidth;
                frameHeightPx = sheetHeight / frameCount;
            } else {
                frameWidthPx = Math.max(1, sheetWidth / frameCount);
                frameHeightPx = sheetHeight;
                Logs.logWarning("Sprite sheet '" + animGlyph.getName() + "' has non-divisible dimensions; " +
                        "reset advance may be approximate.");
            }

            animGlyph.setProcessed(spriteSheetPath, frameWidthPx, frameHeightPx);
            generateAnimationFont(animGlyph);
        } catch (IOException e) {
            Logs.logError("Failed to process sprite sheet for: " + animGlyph.getName());
            Logs.debug(e);
        }
    }

    /**
     * Prepares the sprite sheet for animation, converting vertical to horizontal if needed.
     */
    private BufferedImage prepareAnimationSpriteSheet(AnimatedGlyph animGlyph, BufferedImage image) {
        int frameCount = animGlyph.getFrameCount();
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        boolean widthDiv = imageWidth % frameCount == 0;
        boolean heightDiv = imageHeight % frameCount == 0;

        if (!heightDiv && !widthDiv) {
            Logs.logWarning("Sprite sheet dimensions (" + imageWidth + "x" + imageHeight +
                    ") are not divisible by frame count (" + frameCount + ") for: " + animGlyph.getName());
        }

        boolean vertical = heightDiv && (!widthDiv || imageHeight >= imageWidth);
        boolean horizontal = widthDiv && (!heightDiv || imageWidth > imageHeight);

        if (vertical && heightDiv) {
            return convertVerticalToHorizontalStrip(animGlyph, image, frameCount);
        } else if (!horizontal) {
            Logs.logWarning("Unable to determine sprite sheet orientation for: " + animGlyph.getName());
        }
        return image;
    }

    /**
     * Converts a vertical sprite sheet to horizontal strip format.
     */
    private BufferedImage convertVerticalToHorizontalStrip(AnimatedGlyph animGlyph, BufferedImage image, int frameCount) {
        int frameHeight = image.getHeight() / frameCount;
        int frameWidth = image.getWidth();
        if (frameHeight <= 0) {
            Logs.logWarning("Invalid frame height for animated glyph '" + animGlyph.getName() + "'");
            return null;
        }

        BufferedImage horizontalStrip = new BufferedImage(frameWidth * frameCount, frameHeight,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = horizontalStrip.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        for (int i = 0; i < frameCount; i++) {
            BufferedImage frame = image.getSubimage(0, i * frameHeight, frameWidth, frameHeight);
            graphics.drawImage(frame, i * frameWidth, 0, null);
        }
        graphics.dispose();
        return horizontalStrip;
    }

    /**
     * Writes the sprite sheet to virtual files if it was generated, returns the resource path.
     */
    private String writeSpriteSheetIfNeeded(AnimatedGlyph animGlyph, BufferedImage sheetImage, boolean generatedStrip) {
        String texturePath = animGlyph.getTexturePath();
        String namespace = "minecraft";
        String relativePath = texturePath;

        if (texturePath.contains(":")) {
            String[] split = texturePath.split(":", 2);
            namespace = split[0];
            relativePath = split[1];
        }
        if (relativePath.endsWith(".png")) {
            relativePath = relativePath.substring(0, relativePath.length() - 4);
        }

        String finalPath = generatedStrip ? relativePath + "_strip" : relativePath;
        String spriteSheetPath = namespace + ":" + finalPath + ".png";

        if (generatedStrip) {
            String filePath = finalPath + ".png";
            int lastSlash = filePath.lastIndexOf('/');
            String folder = "assets/" + namespace + "/textures";
            String name = filePath;
            if (lastSlash >= 0) {
                folder = folder + "/" + filePath.substring(0, lastSlash);
                name = filePath.substring(lastSlash + 1);
            }
            writeImageToVirtual(folder, name, sheetImage);
        }
        return spriteSheetPath;
    }

    /**
     * Generates the font file for an animated glyph.
     */
    private void generateAnimationFont(AnimatedGlyph animGlyph) {
        JsonObject fontJson = animGlyph.toFontJson();
        if (fontJson != null) {
            ResourcePack.writeStringToVirtual("assets/oraxen/font/animations", animGlyph.getName() + ".json",
                    fontJson.toString());
            if (Settings.DEBUG.toBool()) {
                Logs.logSuccess("Generated animation font for: " + animGlyph.getName() +
                        " (" + animGlyph.getFrameCount() + " frames @ " + animGlyph.getFps() + " fps)");
            }
        }
    }


    private void writeImageToVirtual(String folder, String name, BufferedImage image) {
        folder = !folder.endsWith("/") ? folder : folder.substring(0, folder.length() - 1);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            ResourcePack.addOutputFiles(new VirtualFile(folder, name, new ByteArrayInputStream(outputStream.toByteArray())));
        } catch (IOException e) {
            Logs.logError("Failed to write generated texture: " + folder + "/" + name);
            if (Settings.DEBUG.toBool())
                e.printStackTrace();
        }
    }


}
