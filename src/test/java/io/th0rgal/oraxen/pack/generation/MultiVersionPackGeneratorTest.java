package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.configs.ConfigsManager;
import io.th0rgal.oraxen.configs.Settings;
import io.th0rgal.oraxen.utils.HashUtils;
import io.th0rgal.oraxen.utils.MinecraftVersion;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.VirtualFile;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Isolated
class MultiVersionPackGeneratorTest {

    @TempDir
    File tempDir;

    @Test
    void generatedCoreShaderFilterMatchesMaterializedShaderPath() throws Exception {
        assertTrue(excludesGeneratedShader("assets/minecraft/shaders/core/rendertype_text.vsh"));
    }

    @Test
    void generatedCoreShaderFilterRemovesOverlayPathsForLegacyTargets() throws Exception {
        assertTrue(excludesGeneratedShader("overlay_1_21_4/assets/minecraft/shaders/core/rendertype_text.vsh"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void versionedPackMetadataPrefersRootWhenAvailable(boolean hasRoot) throws Exception {
        var settings = new YamlConfiguration();
        settings.set(Settings.GENERATE_MCMETA.getPath(), true);
        settings.set(Settings.COMPRESSION.getPath(), "BEST_COMPRESSION");
        settings.set(Settings.COMMENT.getPath(), "");
        settings.set(Settings.PACK_SIZE_SPOOFING.getPath(), false);
        OraxenPlugin plugin = mock(OraxenPlugin.class);
        ConfigsManager configs = mock(ConfigsManager.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(plugin.getConfigsManager()).thenReturn(configs);
        when(configs.getSettings()).thenReturn(settings);

        var output = new ArrayList<VirtualFile>();
        if (hasRoot) {
            output.add(new VirtualFile("", "pack.mcmeta", new ByteArrayInputStream("""
                    {"sodium":{"ignored_shaders":["fog.glsl","terrain.vsh"]}}
                    """.getBytes(StandardCharsets.UTF_8))));
        }
        output.add(new VirtualFile("extras", "pack.mcmeta", new ByteArrayInputStream("""
                {"sodium":{"ignored_shaders":["nested.glsl"]}}
                """.getBytes(StandardCharsets.UTF_8))));

        MinecraftVersion serverVersion = new MinecraftVersion("1.21.4");
        Settings.invalidateCache();
        try (var plugins = mockStatic(OraxenPlugin.class);
             var bukkit = mockStatic(Bukkit.class);
             var versions = mockStatic(MinecraftVersion.class);
             var logs = mockStatic(Logs.class);
             var scheduler = mockStatic(SchedulerUtil.class)) {
            plugins.when(OraxenPlugin::get).thenReturn(plugin);
            bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
            versions.when(MinecraftVersion::getCurrentVersion).thenReturn(serverVersion);
            new MultiVersionPackGenerator(tempDir).generateMultipleVersions(output, false);
        } finally {
            Settings.invalidateCache();
        }

        var expectedShaders = JsonParser.parseString(hasRoot ? "[\"fog.glsl\",\"terrain.vsh\"]" : "[\"nested.glsl\"]");
        for (var target : Map.of("pack_1_21_3.zip", 15, "pack_1_21_4.zip", 46).entrySet()) {
            try (var zip = new ZipFile(new File(tempDir, target.getKey()))) {
                var entry = zip.getEntry("pack.mcmeta");
                assertNotNull(entry);
                try (var stream = zip.getInputStream(entry)) {
                    var metadata = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    assertEquals(expectedShaders, metadata.getAsJsonObject("sodium").get("ignored_shaders"));
                    assertEquals(target.getValue().intValue(), metadata.getAsJsonObject("pack").get("pack_format").getAsInt());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.21.10", "1.21.11"})
    void multiVersionArchivesKeepLegacyBaseAndOverlaySplitAtlasesAtFormat75(String server) throws Exception {
        var settings = new YamlConfiguration();
        settings.set(Settings.GENERATE_MCMETA.getPath(), true);
        settings.set(Settings.COMPRESSION.getPath(), "BEST_COMPRESSION");
        settings.set(Settings.COMMENT.getPath(), "");
        settings.set(Settings.PACK_SIZE_SPOOFING.getPath(), false);
        settings.set(Settings.DEBUG.getPath(), false);
        var plugin = mock(OraxenPlugin.class);
        var configs = mock(ConfigsManager.class);
        when(plugin.getConfigsManager()).thenReturn(configs);
        when(configs.getSettings()).thenReturn(settings);

        String atlasFolder = "assets/minecraft/atlases";
        String overlayFolder = "oraxen_split_atlases/" + atlasFolder;
        String blocks = """
                {"sources":[{"type":"single","resource":"minecraft:item/diamond","sprite":"minecraft:item/diamond"}]}
                """;
        String items = """
                {"sources":[{"type":"single","resource":"oraxen:custom/icon"}]}
                """;
        var output = new ArrayList<VirtualFile>();
        output.add(packFile("", "pack.mcmeta", """
                {"pack":{"pack_format":69,"min_format":46,"max_format":999},
                "overlays":{"entries":[{"directory":"existing","min_format":75,"max_format":999}]}}
                """));
        output.add(packFile(atlasFolder, "blocks.json", blocks));
        output.add(packFile(atlasFolder, "items.json", items));

        File fallback = new File(tempDir, "pack.zip");
        Settings.invalidateCache();
        try (var plugins = mockStatic(OraxenPlugin.class);
             var bukkit = mockStatic(Bukkit.class);
             var versions = mockStatic(MinecraftVersion.class);
             var logs = mockStatic(Logs.class);
             var scheduler = mockStatic(SchedulerUtil.class)) {
            plugins.when(OraxenPlugin::get).thenReturn(plugin);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            versions.when(MinecraftVersion::getCurrentVersion).thenReturn(new MinecraftVersion(server));
            new MultiVersionPackGenerator(tempDir, fallback, Map.of()).generateMultipleVersions(output, false);
        } finally {
            Settings.invalidateCache();
        }

        for (var target : Map.of("pack_1_21_3.zip", 15, "pack_1_21_4.zip", 46, "pack.zip", 69).entrySet()) {
            try (var zip = new ZipFile(new File(tempDir, target.getKey()))) {
                var metadata = readJson(zip, "pack.mcmeta");
                assertEquals(target.getValue().intValue(), metadata.getAsJsonObject("pack").get("pack_format").getAsInt());
                var entries = metadata.getAsJsonObject("overlays").getAsJsonArray("entries");
                boolean splitBase = target.getValue() == 69 && server.equals("1.21.11");
                boolean hasSplitOverlay = target.getValue() != 15 && !splitBase;
                assertEquals(hasSplitOverlay ? 2 : 1, entries.size());
                assertEquals("existing", entries.get(entries.size() - 1).getAsJsonObject().get("directory").getAsString());
                assertEquals(splitBase ? 1 : 2, readJson(zip, atlasFolder + "/blocks.json").getAsJsonArray("sources").size());
                if (splitBase) {
                    assertEquals(JsonParser.parseString(items), readJson(zip, atlasFolder + "/items.json"));
                } else {
                    assertNull(zip.getEntry(atlasFolder + "/items.json"));
                }
                if (hasSplitOverlay) {
                    var overlay = entries.get(0).getAsJsonObject();
                    assertEquals("oraxen_split_atlases", overlay.get("directory").getAsString());
                    assertEquals(75, overlay.get("min_format").getAsInt());
                    assertEquals(999, overlay.get("max_format").getAsInt());
                    assertEquals(JsonParser.parseString(blocks), readJson(zip, overlayFolder + "/blocks.json"));
                    assertEquals(JsonParser.parseString(items), readJson(zip, overlayFolder + "/items.json"));
                } else {
                    assertNull(zip.getEntry(overlayFolder + "/blocks.json"));
                    assertNull(zip.getEntry(overlayFolder + "/items.json"));
                }
            }
        }
    }

    private static VirtualFile packFile(String directory, String name, String content) {
        return new VirtualFile(directory, name, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject readJson(ZipFile zip, String path) throws Exception {
        var entry = zip.getEntry(path);
        assertNotNull(entry, path);
        try (var stream = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private boolean excludesGeneratedShader(String path) throws Exception {
        byte[] content = "generated shader".getBytes(StandardCharsets.UTF_8);
        MultiVersionPackGenerator generator = new MultiVersionPackGenerator(tempDir,
                Map.of(path, HashUtils.bytesToHex(MessageDigest.getInstance("SHA-256").digest(content))));

        Method method = MultiVersionPackGenerator.class.getDeclaredMethod(
                "shouldExcludeGeneratedCoreShader", MinecraftVersion.class, String.class, byte[].class);
        method.setAccessible(true);

        return (boolean) method.invoke(generator, new MinecraftVersion("1.20.2"), path, content);
    }
}
