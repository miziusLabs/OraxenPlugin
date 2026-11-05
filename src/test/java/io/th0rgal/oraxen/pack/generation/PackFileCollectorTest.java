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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class PackFileCollectorTest {

    private final YamlConfiguration settings = new YamlConfiguration();
    private MockedStatic<OraxenPlugin> pluginAccess;
    private MockedStatic<Logs> logs;

    @BeforeEach
    void setUp() {
        OraxenPlugin plugin = mock(OraxenPlugin.class);
        ConfigsManager configs = mock(ConfigsManager.class);
        when(plugin.getConfigsManager()).thenReturn(configs);
        when(configs.getSettings()).thenReturn(settings);
        pluginAccess = mockStatic(OraxenPlugin.class);
        pluginAccess.when(OraxenPlugin::get).thenReturn(plugin);
        logs = mockStatic(Logs.class);
        settings.set(Settings.DEBUG.getPath(), false);
        Settings.invalidateCache();
    }

    @AfterEach
    void tearDown() {
        Settings.invalidateCache();
        logs.close();
        pluginAccess.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"modelengine:oog_pet", "modelengine:entity/oog_pet"})
    void missingNamespacedTextureIsReportedWithoutThrowing(String reference) {
        VirtualFile model = model("{\"textures\":{\"pet\":\"" + reference + "\"}}");

        Set<String> malformed = PackFileCollector.verifyPackFormatting(List.of(model));

        assertEquals(Set.of(model.getPath()), malformed);
        logs.verify(() -> Logs.logWarning(contains(reference)));
    }

    @Test
    void existingNamespacedPngIsAcceptedAndBothStreamsRemainReadable() throws IOException {
        byte[] png = onePixelPng();
        String modelJson = "{\"textures\":{\"pet\":\"modelengine:entity/oog_pet\"}}";
        VirtualFile model = model(modelJson);
        VirtualFile texture = new VirtualFile("assets/modelengine/textures/entity", "oog_pet.png",
                new ByteArrayInputStream(png));

        Set<String> malformed = PackFileCollector.verifyPackFormatting(List.of(model, texture));

        assertTrue(malformed.isEmpty());
        assertArrayEquals(modelJson.getBytes(StandardCharsets.UTF_8), read(model));
        assertArrayEquals(png, read(texture));
    }

    @ParameterizedTest
    @ValueSource(strings = {"oraxen:item/stone", "oraxen:stone"})
    void customNamespaceDoesNotInheritVanillaMaterialExemptions(String reference) {
        VirtualFile model = model("{\"textures\":{\"item\":\"" + reference + "\"}}");

        Set<String> malformed = PackFileCollector.verifyPackFormatting(List.of(model));

        assertEquals(Set.of(model.getPath()), malformed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"modelengine:entity:oog_pet", "modelengine:", ":entity/oog_pet", "modelengine:entity/oog_pet:"})
    void malformedNamespacedReferencesAreReportedWithoutAborting(String reference) {
        VirtualFile model = model("{\"textures\":{\"pet\":\"" + reference + "\"}}");

        Set<String> malformed = PackFileCollector.verifyPackFormatting(List.of(model));

        assertEquals(Set.of(model.getPath()), malformed);
    }

    @Test
    void textureVariablesAndVanillaTextureExemptionsRemainAccepted() {
        VirtualFile model = model("{\"textures\":{"
                + "\"variable\":\"#layer0\","
                + "\"item\":\"item/stone\","
                + "\"block\":\"minecraft:block/stone\","
                + "\"entity\":\"entity/iron_golem\","
                + "\"material\":\"minecraft:stone\""
                + "}}");

        Set<String> malformed = PackFileCollector.verifyPackFormatting(List.of(model));

        assertTrue(malformed.isEmpty());
    }

    private static VirtualFile model(String content) {
        return new VirtualFile("assets/modelengine/models/entity", "oog_pet.json",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] onePixelPng() throws IOException {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static byte[] read(VirtualFile file) throws IOException {
        try (var input = file.getInputStream()) {
            return input.readAllBytes();
        }
    }
}
