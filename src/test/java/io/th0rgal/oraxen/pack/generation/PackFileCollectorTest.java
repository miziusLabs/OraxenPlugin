package io.th0rgal.oraxen.pack.generation;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.configs.ConfigsManager;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.utils.VirtualFile;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class PackFileCollectorTest {
    private MockedStatic<OraxenPlugin> pluginAccess;
    private MockedStatic<Logs> logs;

    @BeforeEach
    void setUp() {
        OraxenPlugin plugin = mock(OraxenPlugin.class);
        ConfigsManager configs = mock(ConfigsManager.class);
        YamlConfiguration settings = new YamlConfiguration();
        settings.set(Settings.DEBUG.getPath(), false);
        when(plugin.getConfigsManager()).thenReturn(configs);
        when(configs.getSettings()).thenReturn(settings);
        pluginAccess = mockStatic(OraxenPlugin.class);
        pluginAccess.when(OraxenPlugin::get).thenReturn(plugin);
        logs = mockStatic(Logs.class);
        Settings.invalidateCache();
    }

    @AfterEach
    void tearDown() {
        Settings.invalidateCache();
        logs.close();
        pluginAccess.close();
    }

    @Test
    void validatesBaseAndImportedAssetsTogetherAndLogsOneSuccessSummary() throws IOException {
        List<VirtualFile> files = new ArrayList<>(List.of(
                file("assets/example/models/item/base.json", "{\"textures\":{\"layer0\":\"example:item/shared\"}}"),
                file("oraxen_import_0/assets/example/textures/item/shared.png", png())));

        Map<String, Set<String>> malformedFiles = PackFileCollector.verifyPackFormatting(files);

        assertTrue(malformedFiles.isEmpty());
        logs.verify(() -> Logs.logSuccess("No broken models or textures were found in the resourcepack"), times(1));
    }

    @Test
    void keepsMalformedTexturePathsScopedToTheirAssetLayer() throws IOException {
        String malformedTexture = "assets/example/textures/item/broken.png";
        List<VirtualFile> files = new ArrayList<>(List.of(
                file("assets/example/models/item/base.json", "{\"textures\":{\"layer0\":\"block/stone\"}}"),
                file(malformedTexture, "not a png"),
                file("oraxen_import_0/" + malformedTexture, "also not a png")));

        Map<String, Set<String>> malformedFiles = PackFileCollector.verifyPackFormatting(files);

        assertEquals(Set.of(malformedTexture), malformedFiles.get(""));
        assertEquals(Set.of(malformedTexture), malformedFiles.get("oraxen_import_0/"));
        logs.verify(() -> Logs.logError("Pack contains malformed texture(s) and/or model(s)"), times(1));
        logs.verify(() -> Logs.logSuccess("No broken models or textures were found in the resourcepack"), times(0));
    }

    private static VirtualFile file(String path, String content) {
        return file(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static VirtualFile file(String path, byte[] content) {
        int slash = path.lastIndexOf('/');
        return new VirtualFile(path.substring(0, slash), path.substring(slash + 1),
                new ByteArrayInputStream(content));
    }

    private static byte[] png() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", output);
        return output.toByteArray();
    }
}
