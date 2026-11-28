package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AtlasGeneratorTest {

    private final YamlConfiguration settings = new YamlConfiguration();
    private MockedStatic<OraxenPlugin> pluginAccess;
    private MockedStatic<Logs> logs;

    @BeforeEach
    void setUp() {
        OraxenPlugin plugin = mock(OraxenPlugin.class);
        when(plugin.namespace()).thenReturn("oraxen");
        ConfigsManager configs = mock(ConfigsManager.class);
        when(plugin.getConfigsManager()).thenReturn(configs);
        when(configs.getSettings()).thenReturn(settings);
        pluginAccess = mockStatic(OraxenPlugin.class);
        pluginAccess.when(OraxenPlugin::get).thenReturn(plugin);
        logs = mockStatic(Logs.class);
        settings.set(Settings.DEBUG.getPath(), false);
        settings.set(Settings.EXCLUDE_MALFORMED_ATLAS.getPath(), false);
        settings.set(Settings.EXCLUDE_UNREFERENCED_ATLAS.getPath(), true);
        settings.set(Settings.ATLAS_GENERATION_TYPE.getPath(), "DIRECTORY");
        Settings.invalidateCache();
    }

    @AfterEach
    void tearDown() {
        Settings.invalidateCache();
        logs.close();
        pluginAccess.close();
    }

    @Test
    void directoryAtlasKeepsNestedDirectoriesUnlessAnAncestorIsListed() {
        settings.set(Settings.EXCLUDE_UNREFERENCED_ATLAS.getPath(), false);
        Settings.invalidateCache();
        List<VirtualFile> output = new ArrayList<>(List.of(
                texture("assets/oraxen/textures/block/stone.png"),
                texture("assets/oraxen/textures/block/ores/ruby.png"),
                texture("assets/oraxen/textures/block/ores/deep/sapphire.png"),
                texture("assets/oraxen/textures/custom/armor/helmet.png"),
                texture("assets/oraxen/textures/custom/armor/gold/boots.png"),
                texture("assets/oraxen/textures/icon.png")));

        AtlasGenerator.generateAtlasFile(output, Set.of());

        Set<String> expected = Set.of(
                "{\"type\":\"directory\",\"source\":\"block\",\"prefix\":\"block/\"}",
                "{\"type\":\"directory\",\"source\":\"custom/armor\",\"prefix\":\"custom/armor/\"}",
                "{\"type\":\"single\",\"resource\":\"oraxen:icon\",\"sprite\":\"oraxen:icon\"}");
        assertEquals(expected, atlasSources(output));
    }

    @Test
    void directoryAtlasUsesSinglesWhenSiblingsAreUnreferencedOrAssignedElsewhere() {
        List<VirtualFile> output = new ArrayList<>(List.of(
                texture("assets/oraxen/textures/shared/icon.png"),
                texture("assets/other/textures/shared/unreferenced.png"),
                texture("assets/other/textures/shared/block_mesh.png"),
                file("assets/oraxen/models/item/icon.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"oraxen:shared/icon"}}
                        """),
                file("assets/minecraft/items/icon.json", """
                        {"model":{"type":"minecraft:model","model":"oraxen:item/icon"}}
                        """),
                file("assets/oraxen/models/block/mesh.json", """
                        {"textures":{"all":"other:shared/block_mesh"},"elements":[{}]}
                        """),
                file("assets/oraxen/blockstates/mesh.json", """
                        {"variants":{"":{"model":"oraxen:block/mesh"}}}
                        """)));

        AtlasGenerator.generateAtlasFiles(output, Set.of(), true);

        JsonArray blockSources = atlas(output, "blocks.json").getAsJsonArray("sources");
        JsonArray itemSources = atlas(output, "items.json").getAsJsonArray("sources");
        assertEquals(Set.of("other:shared/block_mesh"), resources(blockSources));
        assertEquals(Set.of("oraxen:shared/icon"), resources(itemSources));
        assertTrue(blockSources.asList().stream().noneMatch(this::isDirectorySource));
        assertTrue(itemSources.asList().stream().noneMatch(this::isDirectorySource));
    }

    @Test
    void directoryAtlasDoesNotPullVanillaFoldersOntoTheOppositeAtlas() {
        List<VirtualFile> output = new ArrayList<>(List.of(
                texture("assets/oraxen/textures/block/icon.png"),
                texture("assets/oraxen/textures/item/mesh.png"),
                file("assets/oraxen/models/item/icon.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"oraxen:block/icon"}}
                        """),
                file("assets/oraxen/models/block/mesh.json", """
                        {"textures":{"all":"oraxen:item/mesh"},"elements":[{}]}
                        """),
                file("assets/oraxen/blockstates/mesh.json", """
                        {"variants":{"":{"model":"oraxen:block/mesh"}}}
                        """)));

        AtlasGenerator.generateAtlasFiles(output, Set.of(), true);

        JsonObject blocks = atlas(output, "blocks.json");
        JsonObject items = atlas(output, "items.json");
        JsonArray blockSources = blocks.getAsJsonArray("sources");
        JsonArray itemSources = items.getAsJsonArray("sources");
        assertEquals(Set.of("oraxen:item/mesh"), resources(blockSources));
        assertEquals(Set.of("oraxen:block/icon"), resources(itemSources));
        assertExactFilter(blockSources.get(1), "oraxen", "block/icon");
        assertExactFilter(itemSources.get(1), "oraxen", "item/mesh");

        byte[] merged = AtlasGenerator.mergeSplitAtlasesForLegacyTarget(
                blocks.toString().getBytes(StandardCharsets.UTF_8), items.toString().getBytes(StandardCharsets.UTF_8));
        JsonArray mergedSources = JsonParser.parseString(new String(merged, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("sources");
        assertEquals(2, mergedSources.size());
        assertEquals(Set.of("oraxen:item/mesh", "oraxen:block/icon"), resources(mergedSources));
    }

    @Test
    void excludesUnreferencedTexturesByDefaultAndCanRestoreTheirInclusion() {
        List<VirtualFile> defaultOutput = new ArrayList<>(List.of(texture("assets/oraxen/textures/unreferenced.png")));
        AtlasGenerator.generateAtlasFiles(defaultOutput, Set.of(), true);
        assertTrue(atlas(defaultOutput, "blocks.json").getAsJsonArray("sources").isEmpty());

        settings.set(Settings.EXCLUDE_UNREFERENCED_ATLAS.getPath(), false);
        Settings.invalidateCache();
        List<VirtualFile> legacyOutput = new ArrayList<>(List.of(texture("assets/oraxen/textures/unreferenced.png")));
        AtlasGenerator.generateAtlasFiles(legacyOutput, Set.of(), true);
        assertTrue(atlasSources(legacyOutput).contains(
                "{\"type\":\"single\",\"resource\":\"oraxen:unreferenced\",\"sprite\":\"oraxen:unreferenced\"}"));
    }

    @Test
    void filtersBridgedVanillaItemTextureAndKeepsItInLegacyBlocksAtlas() {
        List<VirtualFile> output = new ArrayList<>(List.of(
                file("assets/oraxen/models/block/lantern.json", """
                        {"textures":{"all":"minecraft:item/diamond"},"elements":[{}]}
                        """),
                file("assets/oraxen/blockstates/lantern.json", """
                        {"variants":{"":{"model":"oraxen:block/lantern"}}}
                        """),
                file("assets/minecraft/atlases/items.json", """
                        {"sources":[
                          {"type":"single","resource":"minecraft:item/diamond"},
                          {"type":"directory","source":"item","prefix":"item/"},
                          {"type":"filter","pattern":{"namespace":"minecraft","path":"item/emerald"}}
                        ]}
                        """)));

        AtlasGenerator.generateAtlasFiles(output, Set.of(), true);

        JsonObject blocksAtlas = atlas(output, "blocks.json");
        JsonObject itemsAtlas = atlas(output, "items.json");
        assertNotNull(itemsAtlas);
        assertTrue(atlasSources(output).contains(
                "{\"type\":\"single\",\"resource\":\"minecraft:item/diamond\",\"sprite\":\"minecraft:item/diamond\"}"));
        JsonArray itemSources = itemsAtlas.getAsJsonArray("sources");
        JsonObject filter = itemSources.get(itemSources.size() - 1).getAsJsonObject();
        assertExactFilter(filter, "minecraft", "item/diamond");
        String filterPath = filter.getAsJsonObject("pattern").get("path").getAsString();
        assertTrue(itemSources.asList().stream().anyMatch(source ->
                source.isJsonObject() && "single".equals(source.getAsJsonObject().get("type").getAsString())
                        && "minecraft:item/diamond".equals(source.getAsJsonObject().get("resource").getAsString())));
        assertTrue(itemSources.asList().stream().anyMatch(this::isDirectorySource));

        byte[] legacyBytes = AtlasGenerator.mergeSplitAtlasesForLegacyTarget(
                blocksAtlas.toString().getBytes(StandardCharsets.UTF_8),
                itemsAtlas.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject legacyAtlas = JsonParser.parseString(new String(legacyBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(-1, indexOfFilterPath(legacyAtlas.getAsJsonArray("sources"), filterPath));
        assertTrue(indexOfFilterPath(legacyAtlas.getAsJsonArray("sources"), "item/emerald") >= 0);
        assertTrue(legacyAtlas.getAsJsonArray("sources").asList().stream().anyMatch(source ->
                source.isJsonObject() && source.getAsJsonObject().has("resource")
                        && "minecraft:item/diamond".equals(source.getAsJsonObject().get("resource").getAsString())));
    }

    @Test
    void legacyDirectoryAtlasRetainsTheChildThatRestoresFilteredSprites() {
        byte[] blocks = """
                {"sources":[{"type":"directory","source":"block","prefix":"block/"},
                {"type":"directory","source":"block/custom","prefix":"block/custom/"},
                {"type":"filter","pattern":{"path":"block/custom/icon"}}]}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] items = """
                {"sources":[{"type":"directory","source":"block/custom","prefix":"block/custom/"}]}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] merged = AtlasGenerator.mergeSplitAtlasesForLegacyTarget(blocks, items);
        assertEquals(JsonParser.parseString("""
                {"sources":[{"type":"directory","source":"block","prefix":"block/"},
                {"type":"filter","pattern":{"path":"block/custom/icon"}},
                {"type":"directory","source":"block/custom","prefix":"block/custom/"}]}
                """), JsonParser.parseString(new String(merged, StandardCharsets.UTF_8)));
    }

    @Test
    void legacyMergePreservesAnImportedFilterMatchingAnImportedSingle() {
        byte[] blocks = """
                {"sources":[{"type":"single","resource":"minecraft:item/emerald"}]}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] items = """
                {"sources":[{"type":"filter","pattern":{"namespace":"minecraft","path":"item/emerald"}}]}
                """.getBytes(StandardCharsets.UTF_8);

        byte[] merged = AtlasGenerator.mergeSplitAtlasesForLegacyTarget(blocks, items);
        JsonObject legacyAtlas = JsonParser.parseString(new String(merged, StandardCharsets.UTF_8)).getAsJsonObject();

        assertTrue(indexOfFilterPath(legacyAtlas.getAsJsonArray("sources"), "item/emerald") >= 0);
    }

    @Test
    void legacyMergePreservesImportedFilterEvenForAnExactBlockSprite() {
        byte[] blocks = """
                {"sources":[{"type":"single","resource":"minecraft:item/emerald","sprite":"minecraft:item/emerald"}]}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] items = """
                {"sources":[{"type":"filter","pattern":{"namespace":"minecraft","path":"item/emerald"}}]}
                """.getBytes(StandardCharsets.UTF_8);

        byte[] merged = AtlasGenerator.mergeSplitAtlasesForLegacyTarget(blocks, items);
        JsonObject legacyAtlas = JsonParser.parseString(new String(merged, StandardCharsets.UTF_8)).getAsJsonObject();

        assertTrue(indexOfFilterPath(legacyAtlas.getAsJsonArray("sources"), "item/emerald") >= 0);
    }

    private static Set<String> atlasSources(List<VirtualFile> output) {
        JsonObject atlas = atlas(output, "blocks.json");
        Set<String> sources = new HashSet<>();
        for (JsonElement source : atlas.getAsJsonArray("sources")) sources.add(source.toString());
        return sources;
    }

    private static Set<String> resources(JsonArray sources) {
        Set<String> resources = new HashSet<>();
        for (JsonElement source : sources) {
            if (source.isJsonObject() && source.getAsJsonObject().has("resource")) {
                resources.add(source.getAsJsonObject().get("resource").getAsString());
            }
        }
        return resources;
    }

    private boolean isDirectorySource(JsonElement source) {
        return source.isJsonObject() && source.getAsJsonObject().has("type")
                && "directory".equals(source.getAsJsonObject().get("type").getAsString());
    }

    private static void assertExactFilter(JsonElement source, String namespace, String path) {
        JsonObject filter = source.getAsJsonObject();
        assertEquals("filter", filter.get("type").getAsString());
        JsonObject pattern = filter.getAsJsonObject("pattern");
        var namespaceRegex = java.util.regex.Pattern.compile(pattern.get("namespace").getAsString()).asPredicate();
        var pathRegex = java.util.regex.Pattern.compile(pattern.get("path").getAsString()).asPredicate();
        assertTrue(namespaceRegex.test(namespace));
        assertFalse(namespaceRegex.test(namespace + "_other"));
        assertTrue(pathRegex.test(path));
        assertFalse(pathRegex.test(path + "_axe"));
    }

    private static int indexOfFilterPath(JsonArray sources, String path) {
        for (int i = 0; i < sources.size(); i++) {
            JsonElement source = sources.get(i);
            if (source.isJsonObject() && source.getAsJsonObject().has("pattern")
                    && source.getAsJsonObject().getAsJsonObject("pattern").has("path")
                    && path.equals(source.getAsJsonObject().getAsJsonObject("pattern").get("path").getAsString())) {
                return i;
            }
        }
        return -1;
    }

    private static JsonObject atlas(List<VirtualFile> output, String name) {
        return output.stream()
                .filter(file -> file.getPath().equals("assets/minecraft/atlases/" + name))
                .findFirst().orElseThrow().toJsonObject();
    }

    private static VirtualFile file(String path, String content) {
        int separator = path.lastIndexOf('/');
        return new VirtualFile(path.substring(0, separator), path.substring(separator + 1),
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static VirtualFile texture(String path) {
        return new VirtualFile(path.substring(0, path.lastIndexOf('/')), path.substring(path.lastIndexOf('/') + 1),
                new ByteArrayInputStream(new byte[0]));
    }
}
