package io.th0rgal.oraxen.pack.generation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.th0rgal.oraxen.pack.generation.AtlasDependencyResolver.AtlasResolutionResult;
import io.th0rgal.oraxen.pack.generation.AtlasDependencyResolver.ModelRenderKind;
import io.th0rgal.oraxen.pack.generation.AtlasDependencyResolver.TextureRef;
import io.th0rgal.oraxen.utils.VirtualFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AtlasDependencyResolverTest {

    private static VirtualFile virtualFile(String folder, String name, String content) {
        return new VirtualFile(folder, name, new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void separates2DItemTexturesAndBridges3DVanillaItemReferences() {
        List<VirtualFile> files = new ArrayList<>();

        // 1. Custom 2D item texture + model
        files.add(virtualFile("assets/oraxen/textures/item", "ruby.png", "PNG_BYTES"));
        files.add(virtualFile("assets/oraxen/models/item", "ruby.json", """
                {
                  "parent": "minecraft:item/generated",
                  "textures": {
                    "layer0": "oraxen:item/ruby"
                  }
                }
                """));

        // 2. Custom 3D furniture model that references both a custom texture and a vanilla item texture
        files.add(virtualFile("assets/oraxen/textures/furniture", "table_oak.png", "PNG_BYTES"));
        files.add(virtualFile("assets/oraxen/models/furniture", "table.json", """
                {
                  "textures": {
                    "base": "oraxen:furniture/table_oak",
                    "accent": "#Trim",
                    "Trim": "minecraft:item/diamond"
                  },
                  "elements": [
                    {
                      "from": [0, 0, 0],
                      "to": [16, 16, 16],
                      "faces": {
                        "up": { "texture": "#base" },
                        "north": { "texture": "#accent" }
                      }
                    }
                  ]
                }
                """));
        files.add(virtualFile("assets/oraxen/blockstates", "table.json", """
                {"variants":{"":{"model":"oraxen:furniture/table"}}}
                """));

        // 3. Font-only glyph texture + font JSON
        files.add(virtualFile("assets/oraxen/textures/font", "emoji_smile.png", "PNG_BYTES"));
        files.add(virtualFile("assets/minecraft/font", "default.json", """
                {
                  "providers": [
                    {
                      "type": "bitmap",
                      "file": "oraxen:font/emoji_smile.png",
                      "ascent": 7,
                      "chars": ["\\uE001"]
                    }
                  ]
                }
                """));

        AtlasDependencyResolver resolver = new AtlasDependencyResolver();
        AtlasResolutionResult result = resolver.resolve(files, Collections.emptySet(), Collections.emptySet());

        TextureRef rubyRef = new TextureRef("oraxen", "item/ruby");
        TextureRef tableRef = new TextureRef("oraxen", "furniture/table_oak");
        TextureRef diamondRef = new TextureRef("minecraft", "item/diamond");
        TextureRef emojiRef = new TextureRef("oraxen", "font/emoji_smile");

        // Ruby is a 2D item texture -> present in both itemAtlasTextures (for 1.21.11+/26.x) and blockAtlasTextures (legacy compatibility)
        assertTrue(result.itemAtlasTextures().contains(rubyRef));
        assertTrue(result.blockAtlasTextures().contains(rubyRef));

        // Table is a 3D model texture -> in blockAtlasTextures, NOT in itemAtlasTextures
        assertTrue(result.blockAtlasTextures().contains(tableRef));
        assertFalse(result.itemAtlasTextures().contains(tableRef));

        // Vanilla item/diamond used on a 3D model is bridged into blockAtlasTextures so 1.21.11+/26.x does not fail with multi-atlas error
        assertTrue(result.bridgedVanillaItemTextures().contains(diamondRef));
        assertTrue(result.blockAtlasTextures().contains(diamondRef));

        // Font-only texture is excluded from both atlases
        assertTrue(result.fontOnlyTextures().contains(emojiRef));
        assertFalse(result.blockAtlasTextures().contains(emojiRef));
        assertFalse(result.itemAtlasTextures().contains(emojiRef));
        assertTrue(result.missingTexturesByModel().isEmpty());
    }

    @Test
    void detectsMissingCustomTexturesInModelHierarchy() {
        List<VirtualFile> files = new ArrayList<>();
        files.add(virtualFile("assets/oraxen/models/block", "parent_cube.json", """
                {
                  "parent": "minecraft:block/cube_all",
                  "textures": {
                    "all": "#surface"
                  }
                }
                """));
        files.add(virtualFile("assets/oraxen/models/block", "broken_ore.json", """
                {
                  "parent": "oraxen:block/parent_cube",
                  "textures": {
                    "surface": "oraxen:block/non_existent_ore"
                  }
                }
                """));

        AtlasDependencyResolver resolver = new AtlasDependencyResolver();
        AtlasResolutionResult result = resolver.resolve(files, Collections.emptySet(), Collections.emptySet());

        assertTrue(result.missingTexturesByModel().containsKey("oraxen:block/broken_ore"));
        assertTrue(result.missingTexturesByModel().get("oraxen:block/broken_ore")
                .contains(new TextureRef("oraxen", "block/non_existent_ore")));
    }

    @Test
    void atlasGeneratorEmitsBlocksAndItemsAtlasesForSplitAtlasVersions() {
        List<VirtualFile> files = new ArrayList<>();
        files.add(virtualFile("assets/oraxen/textures/item", "emerald_staff.png", "PNG_BYTES"));
        files.add(virtualFile("assets/oraxen/models/item", "emerald_staff.json", """
                {
                  "parent": "minecraft:item/handheld",
                  "textures": {
                    "layer0": "oraxen:item/emerald_staff"
                  }
                }
                """));

        AtlasGenerator.generateAtlasFiles(files, Collections.emptySet(), true);

        PackAssetIndex index = new PackAssetIndex(files);
        VirtualFile blocksAtlas = index.getFirst("assets/minecraft/atlases/blocks.json");
        VirtualFile itemsAtlas = index.getFirst("assets/minecraft/atlases/items.json");

        assertNotNull(blocksAtlas);
        assertNotNull(itemsAtlas);

        JsonObject itemsJson = AtlasDependencyResolver.readJsonObjectNonDestructive(itemsAtlas);
        assertNotNull(itemsJson);
        JsonArray sources = itemsJson.getAsJsonArray("sources");
        assertNotNull(sources);
        boolean foundStaff = false;
        for (JsonElement el : sources) {
            JsonObject obj = el.getAsJsonObject();
            if ("single".equals(obj.get("type").getAsString())
                    && "oraxen:item/emerald_staff".equals(obj.get("resource").getAsString())) {
                foundStaff = true;
            }
        }
        assertTrue(foundStaff);

        JsonObject blocksJson = AtlasDependencyResolver.readJsonObjectNonDestructive(blocksAtlas);
        assertNotNull(blocksJson);
        for (JsonElement el : blocksJson.getAsJsonArray("sources")) {
            JsonObject obj = el.getAsJsonObject();
            if ("single".equals(obj.get("type").getAsString())) {
                assertFalse("oraxen:item/emerald_staff".equals(obj.get("resource").getAsString()),
                        "2D item sprite must not be duplicated in blocks.json on 1.21.11+/26.x");
            }
        }

        // Legacy multi-version targets (< 1.21.11) merge items.json back into blocks.json
        byte[] mergedBlocksBytes = AtlasGenerator.mergeSplitAtlasesForLegacyTarget(
                blocksJson.toString().getBytes(StandardCharsets.UTF_8),
                itemsJson.toString().getBytes(StandardCharsets.UTF_8)
        );
        VirtualFile mergedBlocksFile = new VirtualFile("assets/minecraft/atlases", "blocks.json",
                new ByteArrayInputStream(mergedBlocksBytes));
        JsonObject mergedBlocksJson = AtlasDependencyResolver.readJsonObjectNonDestructive(mergedBlocksFile);
        assertNotNull(mergedBlocksJson);
        boolean foundStaffInMerged = false;
        for (JsonElement el : mergedBlocksJson.getAsJsonArray("sources")) {
            JsonObject obj = el.getAsJsonObject();
            if ("single".equals(obj.get("type").getAsString())
                    && "oraxen:item/emerald_staff".equals(obj.get("resource").getAsString())) {
                foundStaffInMerged = true;
            }
        }
        assertTrue(foundStaffInMerged, "Merged legacy blocks.json must include 2D item sprites for < 1.21.11 clients");
    }

    @Test
    void packAssetIndexFindsDuplicatesAndNormalizesPaths() {
        PackAssetIndex index = new PackAssetIndex();
        index.add(virtualFile("assets//minecraft//font/", "default.json", "{\"providers\":[]}"));
        index.add(virtualFile("/assets/minecraft/font", "default.json", "{\"providers\":[]}"));
        index.add(virtualFile("assets/minecraft/font", "uniform.json", "{\"providers\":[]}"));

        assertEquals(2, index.getAll("assets/minecraft/font/default.json").size());
        Map<String, List<VirtualFile>> dupes = index.findDuplicates(p -> p.endsWith(".json"));
        assertEquals(1, dupes.size());
        assertTrue(dupes.containsKey("assets/minecraft/font/default.json"));

        // Root-level files without slashes (e.g. pack.mcmeta, pack.png) must be indexed without StringIndexOutOfBoundsException
        index.add(virtualFile("", "pack.mcmeta", "{\"pack\":{\"pack_format\":46}}"));
        index.add(virtualFile("", "pack.png", "PNG"));
        assertNotNull(index.getFirst("pack.mcmeta"));
        assertEquals("pack.mcmeta", index.getFirst("pack.mcmeta").getPath());
        assertNotNull(index.getFirst("pack.png"));
        assertEquals("pack.png", index.getFirst("pack.png").getPath());
    }

    @Test
    void includesModelReferencedTextureFromExcludedDirectoryWhileKeepingUnreferencedExcluded() {
        List<VirtualFile> files = List.of(
                virtualFile("assets/oraxen/textures/gui", "icon.png", "PNG"),
                virtualFile("assets/oraxen/textures/gui", "unreferenced_bg.png", "PNG"),
                virtualFile("assets/oraxen/models/item", "gui_icon_item.json", """
                        {
                          "parent": "minecraft:item/generated",
                          "textures": {
                            "layer0": "oraxen:gui/icon"
                          }
                        }
                        """)
        );

        AtlasDependencyResolver.AtlasResolutionResult result =
                new AtlasDependencyResolver().resolve(files, Set.of(), Set.of(), true);

        AtlasDependencyResolver.TextureRef referencedGui = new AtlasDependencyResolver.TextureRef("oraxen", "gui/icon");
        AtlasDependencyResolver.TextureRef unreferencedGui = new AtlasDependencyResolver.TextureRef("oraxen", "gui/unreferenced_bg");

        assertTrue(result.itemAtlasTextures().contains(referencedGui),
                "Model-referenced texture from excluded directory must be included in its atlas");
        assertFalse(result.blockAtlasTextures().contains(unreferencedGui),
                "Unreferenced gui texture must remain excluded from block atlas");
        assertFalse(result.itemAtlasTextures().contains(unreferencedGui),
                "Unreferenced gui texture must remain excluded from item atlas");
        assertTrue(result.missingTexturesByModel().isEmpty(),
                "Model-referenced texture from excluded directory must not be flagged as missing");
    }

    @Test
    void propagatesAtlasPlacementAcrossTransitiveSharedTextureGroups() {
        List<VirtualFile> files = new ArrayList<>(List.of(
                virtualFile("assets/oraxen/textures/item", "first.png", "PNG"),
                virtualFile("assets/oraxen/textures/shared", "one.png", "PNG"),
                virtualFile("assets/oraxen/textures/shared", "two.png", "PNG"),
                virtualFile("assets/oraxen/textures/item", "last.png", "PNG"),
                virtualFile("assets/oraxen/models/item", "first.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"oraxen:item/first","layer1":"oraxen:shared/one"}}
                        """),
                virtualFile("assets/oraxen/models/block", "middle.json", """
                        {"textures":{"side":"oraxen:shared/one","top":"oraxen:shared/two"},"elements":[{}]}
                        """),
                virtualFile("assets/oraxen/models/item", "last.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"oraxen:shared/two","layer1":"oraxen:item/last"}}
                        """)));

        AtlasResolutionResult result = new AtlasDependencyResolver()
                .resolve(files, Set.of(), Set.of(), true);

        for (String texture : List.of("item/first", "shared/one", "shared/two", "item/last")) {
            TextureRef ref = new TextureRef("oraxen", texture);
            assertTrue(result.blockAtlasTextures().contains(ref), texture + " should follow its connected model group");
            assertFalse(result.itemAtlasTextures().contains(ref), texture + " must not be split from the group");
        }
    }

    @Test
    void resolvesInheritedAndAliasedTexturesForAtlasPlacement() {
        List<VirtualFile> files = List.of(
                virtualFile("assets/oraxen/textures/item", "inherited.png", "PNG"),
                virtualFile("assets/oraxen/textures/item", "own.png", "PNG"),
                virtualFile("assets/oraxen/models/item", "parent.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"#alias","alias":"#base","base":"oraxen:item/inherited"}}
                        """),
                virtualFile("assets/oraxen/models/item", "child.json", """
                        {"parent":"oraxen:item/parent","textures":{"layer1":"oraxen:item/own"}}
                        """));

        AtlasResolutionResult result = new AtlasDependencyResolver()
                .resolve(files, Set.of(), Set.of(), true);

        assertTrue(result.itemAtlasTextures().contains(new TextureRef("oraxen", "item/inherited")));
        assertTrue(result.itemAtlasTextures().contains(new TextureRef("oraxen", "item/own")));
        assertFalse(result.missingTexturesByModel().containsKey("oraxen:item/child"));
    }

    @Test
    void usesRealItemAndBlockReferencesBeforeModelShapeFallbacks() {
        List<VirtualFile> files = List.of(
                virtualFile("assets/oraxen/textures/item", "item_use.png", "PNG"),
                virtualFile("assets/oraxen/textures/block", "block_use.png", "PNG"),
                virtualFile("assets/oraxen/models/block", "item_model.json", """
                        {"textures":{"all":"oraxen:item/item_use"},"elements":[{}]}
                        """),
                virtualFile("assets/oraxen/models/item", "block_model.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"oraxen:block/block_use"}}
                        """),
                virtualFile("assets/minecraft/items", "paper.json", """
                        {"model":{"type":"minecraft:model","model":"oraxen:block/item_model"}}
                        """),
                virtualFile("assets/oraxen/blockstates", "block.json", """
                        {"variants":{"":{"model":"oraxen:item/block_model"}}}
                        """));

        AtlasResolutionResult result = new AtlasDependencyResolver()
                .resolve(files, Set.of(), Set.of(), true);

        assertTrue(result.itemAtlasTextures().contains(new TextureRef("oraxen", "item/item_use")));
        assertFalse(result.blockAtlasTextures().contains(new TextureRef("oraxen", "item/item_use")));
        assertTrue(result.blockAtlasTextures().contains(new TextureRef("oraxen", "block/block_use")));
        assertFalse(result.itemAtlasTextures().contains(new TextureRef("oraxen", "block/block_use")));
    }

    @Test
    void usesVanillaAtlasDefaultsBeforeModelShapeFallbacks() {
        List<VirtualFile> files = List.of(
                virtualFile("assets/minecraft/textures/item", "diamond.png", "PNG"),
                virtualFile("assets/minecraft/textures/entity", "cat.png", "PNG"),
                virtualFile("assets/minecraft/textures/entity/bell", "bell_body.png", "PNG"),
                virtualFile("assets/minecraft/textures/entity/conduit", "closed_eye.png", "PNG"),
                virtualFile("assets/minecraft/textures/trims/items", "helmet_trim.png", "PNG"),
                virtualFile("assets/oraxen/models/block", "custom_model.json", """
                        {"textures":{"all":"minecraft:item/diamond"},"elements":[{}]}
                        """),
                virtualFile("assets/oraxen/models/item", "entity_item.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:entity/cat"}}
                        """),
                virtualFile("assets/oraxen/models/item", "bell_item.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:entity/bell/bell_body"}}
                        """),
                virtualFile("assets/oraxen/models/item", "trim_item.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:trims/items/helmet_trim"}}
                        """),
                virtualFile("assets/oraxen/models/item", "conduit_item.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:entity/conduit/closed_eye"}}
                        """),
                virtualFile("assets/minecraft/items", "entity.json", """
                        {"model":{"type":"minecraft:model","model":"oraxen:item/entity_item"}}
                        """),
                virtualFile("assets/minecraft/items", "bell.json", """
                        {"model":{"type":"minecraft:model","model":"oraxen:item/bell_item"}}
                        """),
                virtualFile("assets/minecraft/items", "trim.json", """
                        {"model":{"type":"minecraft:model","model":"oraxen:item/trim_item"}}
                        """),
                virtualFile("assets/minecraft/items", "conduit.json", """
                        {"model":{"type":"minecraft:model","model":"oraxen:item/conduit_item"}}
                        """),
                virtualFile("assets/minecraft/items", "diamond.json", """
                        {"model":{"type":"minecraft:model","model":"oraxen:block/custom_model"}}
                        """));

        AtlasResolutionResult result = new AtlasDependencyResolver()
                .resolve(files, Set.of(), Set.of(), true);

        TextureRef diamond = new TextureRef("minecraft", "item/diamond");
        assertTrue(result.itemAtlasTextures().contains(diamond));
        assertFalse(result.blockAtlasTextures().contains(diamond));
        assertFalse(result.bridgedVanillaItemTextures().contains(diamond));

        assertTrue(result.itemAtlasTextures().contains(new TextureRef("minecraft", "entity/cat")),
                "Entity textures outside the 1.21.11 block atlas sources must not inherit a broad block default");
        assertTrue(result.blockAtlasTextures().contains(new TextureRef("minecraft", "entity/bell/bell_body")),
                "The bell texture is a single source in the 1.21.11 blocks atlas");
        assertTrue(result.blockAtlasTextures().contains(new TextureRef("minecraft", "entity/conduit/closed_eye")),
                "Conduit textures are a directory source in the 1.21.11 blocks atlas");
        assertTrue(result.itemAtlasTextures().contains(new TextureRef("minecraft", "trims/items/helmet_trim")),
                "The trim texture is generated into the 1.21.11 items atlas");
    }

    @Test
    void uses26_3VanillaBlockAtlasSingleForItemModelTextures() {
        List<VirtualFile> files = List.of(
                virtualFile("assets/minecraft/textures/entity/enchantment", "enchanting_table_book.png", "PNG"),
                virtualFile("assets/oraxen/textures/item", "book_cover.png", "PNG"),
                virtualFile("assets/oraxen/models/item", "enchanting_book.json", """
                        {"parent":"minecraft:item/generated","textures":{"layer0":"minecraft:entity/enchantment/enchanting_table_book","layer1":"oraxen:item/book_cover"}}
                        """),
                virtualFile("assets/minecraft/items", "enchanting_book.json", """
                        {"model":{"type":"minecraft:model","model":"oraxen:item/enchanting_book"}}
                        """));

        AtlasResolutionResult result = new AtlasDependencyResolver()
                .resolve(files, Set.of(), Set.of(), true);

        TextureRef book = new TextureRef("minecraft", "entity/enchantment/enchanting_table_book");
        TextureRef cover = new TextureRef("oraxen", "item/book_cover");
        assertTrue(result.blockAtlasTextures().contains(book));
        assertTrue(result.blockAtlasTextures().contains(cover));
        assertFalse(result.itemAtlasTextures().contains(book));
        assertFalse(result.itemAtlasTextures().contains(cover));
    }

    @Test
    void vanillaBlockDefaultKeepsConnectedItemModelTexturesOnBlocksAtlas() {
        List<VirtualFile> files = List.of(
                virtualFile("assets/oraxen/textures/block", "stone_detail.png", "PNG"),
                virtualFile("assets/oraxen/models/item", "stone_item.json", """
                        {"textures":{"all":"minecraft:block/stone","detail":"oraxen:block/stone_detail"},"elements":[{}]}
                        """),
                virtualFile("assets/minecraft/items", "stone.json", """
                        {"model":{"type":"minecraft:model","model":"oraxen:item/stone_item"}}
                        """));

        AtlasResolutionResult result = new AtlasDependencyResolver()
                .resolve(files, Set.of(), Set.of(), true);

        assertTrue(result.blockAtlasTextures().contains(new TextureRef("oraxen", "block/stone_detail")));
        assertFalse(result.itemAtlasTextures().contains(new TextureRef("minecraft", "block/stone")));
        assertFalse(result.itemAtlasTextures().contains(new TextureRef("oraxen", "block/stone_detail")));
    }
}
