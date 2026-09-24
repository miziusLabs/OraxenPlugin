package io.th0rgal.oraxen.pack.generation.assets;

import io.th0rgal.oraxen.api.OraxenItems;
import io.th0rgal.oraxen.configs.AppearanceMode;
import io.th0rgal.oraxen.items.ItemBuilder;
import io.th0rgal.oraxen.items.OraxenMeta;
import io.th0rgal.oraxen.pack.generation.ModelDefinitionGenerator;
import io.th0rgal.oraxen.pack.generation.ModelGenerator;
import io.th0rgal.oraxen.pack.generation.PredicatesGenerator;
import io.th0rgal.oraxen.pack.generation.ResourcePack;
import io.th0rgal.oraxen.pack.generation.VanillaItemDefinitionGenerator;
import io.th0rgal.oraxen.utils.VersionUtil;
import org.bukkit.Material;

import java.util.*;

/** Builds the item model formats required by the server and client versions. */
public final class ItemAssetGenerator {
    public void generate(boolean multiVersionResolved) {
        final Map<Material, Map<String, ItemBuilder>> texturedItems = extractTexturedItems();
        generateItemAppearanceAssets(texturedItems, multiVersionResolved);
    }

    /**
     * Generates item appearance assets (predicates, item definitions, model definitions)
     * based on the server version and appearance mode configuration.
     */
    private void generateItemAppearanceAssets(Map<Material, Map<String, ItemBuilder>> texturedItems,
            boolean multiVersionResolved) {
        final boolean is1_21_4Plus = VersionUtil.atOrAbove("1.21.4");

        // Whether 1.21.4+ clients will consume this pack: either we are running on
        // such a server, or we are emitting a multi-version pack that targets them.
        final boolean targets1_21_4Plus = is1_21_4Plus || multiVersionResolved;

        if (targets1_21_4Plus) {
            // In multi-version mode, model definitions must always be generated regardless
            // of server version because some target pack versions serve 1.21.4+ clients.
            if (is1_21_4Plus) {
                AppearanceMode.validateAndLogWarnings();
            }

            // Multi-version mode always needs model definitions (some target pack
            // versions serve 1.21.4+ clients regardless of server version).
            // For non-multi-version 1.21.4+, honor the user's item_properties toggle
            // so explicitly disabling that appearance system suppresses the
            // assets/oraxen/items/*.json output as it did pre-#1812.
            if (multiVersionResolved || AppearanceMode.isItemPropertiesEnabled()) {
                generateModelDefinitions(filterForItemModel(texturedItems));
            }

            // In multi-version mode, 1.21.4+ targets ALWAYS need vanilla item definitions
            // as a fallback for clients that cannot use legacy CMD predicates. Otherwise,
            // honor the AppearanceMode toggle.
            if (multiVersionResolved || AppearanceMode.shouldGenerateVanillaItemDefinitions()) {
                boolean useSelect = AppearanceMode.shouldUseSelectForVanillaItemDefs();
                boolean includeBothModes = AppearanceMode.shouldUseBothDispatchModes();
                generateVanillaItemDefinitions(filterForModelData(texturedItems), useSelect, includeBothModes);
            }
        }

        if (is1_21_4Plus) {
            // Multi-version mode ALWAYS needs predicates because older target clients (1.20-1.21.3)
            // cannot use item definitions and rely solely on legacy predicate model overrides.
            // Uses the resolved flag (not the raw setting) to respect single-pack fallback.
            if (AppearanceMode.shouldGenerateLegacyPredicates() || multiVersionResolved) {
                generatePredicates(filterForModelData(texturedItems));
            }
        } else {
            // On 1.21.3- servers, predicates are the primary (or only) item appearance system.
            generatePredicates(filterForModelData(texturedItems));
        }
    }

    private Map<Material, Map<String, ItemBuilder>> extractTexturedItems() {
        final Map<Material, Map<String, ItemBuilder>> texturedItems = new HashMap<>();
        for (final Map.Entry<String, ItemBuilder> entry : OraxenItems.getEntries()) {
            final String itemId = entry.getKey();
            final ItemBuilder item = entry.getValue();
            OraxenMeta oraxenMeta = item.getOraxenMeta();
            if (item.hasOraxenMeta() && oraxenMeta.hasPackInfos()) {
                String modelName = oraxenMeta.getModelName() + ".json";
                String modelPath = oraxenMeta.getModelPath();
                if (oraxenMeta.shouldGenerateModel()) {
                    ResourcePack.writeStringToVirtual(modelPath, modelName, new ModelGenerator(oraxenMeta).getJson().toString());
                }
                final Map<String, ItemBuilder> items = texturedItems.computeIfAbsent(item.getType(),
                        k -> new LinkedHashMap<>());

                // Insert in order of CustomModelData
                List<Map.Entry<String, ItemBuilder>> sortedItems = new ArrayList<>(items.entrySet());
                int insertIndex = 0;
                Integer newCmd = Optional.ofNullable(oraxenMeta.getCustomModelData()).orElse(0);

                for (Map.Entry<String, ItemBuilder> existingEntry : sortedItems) {
                    Integer existingCmd = Optional
                            .ofNullable(existingEntry.getValue().getOraxenMeta().getCustomModelData()).orElse(0);
                    if (existingCmd > newCmd)
                        break;
                    insertIndex++;
                }

                // Rebuild map in correct order
                Map<String, ItemBuilder> newItems = new LinkedHashMap<>();
                for (int i = 0; i < sortedItems.size(); i++) {
                    if (i == insertIndex) {
                        newItems.put(itemId, item);
                    }
                    Map.Entry<String, ItemBuilder> existingEntry = sortedItems.get(i);
                    newItems.put(existingEntry.getKey(), existingEntry.getValue());
                }
                if (insertIndex >= sortedItems.size()) {
                    newItems.put(itemId, item);
                }

                texturedItems.put(item.getType(), newItems);
            }
        }
        return texturedItems;
    }

    private void generatePredicates(final Map<Material, Map<String, ItemBuilder>> texturedItems) {
        for (final Map.Entry<Material, Map<String, ItemBuilder>> texturedItemsEntry : texturedItems.entrySet()) {
            final Material entryMaterial = texturedItemsEntry.getKey();
            final PredicatesGenerator predicatesGenerator = new PredicatesGenerator(entryMaterial,
                    new ArrayList<>(texturedItemsEntry.getValue().values()));
            final String[] vanillaModelPath = (predicatesGenerator.getVanillaModelName(entryMaterial) + ".json")
                    .split("/");
            ResourcePack.writeStringToVirtual("assets/minecraft/models/" + vanillaModelPath[0], vanillaModelPath[1],
                    predicatesGenerator.toJSON().toString());
        }
    }

    /**
     * Generates vanilla item model definitions (assets/minecraft/items/*.json) for 1.21.4+.
     *
     * @param texturedItems    the items to generate definitions for
     * @param useSelect        true for {@code minecraft:select} on strings (MODEL_DATA_IDS),
     *                         false for {@code minecraft:range_dispatch} on floats (MODEL_DATA_FLOAT_LEGACY)
     * @param includeBothModes if true, generates both select (strings) AND range_dispatch (floats)
     *                         dispatchers for maximum compatibility with external plugins
     */
    private void generateVanillaItemDefinitions(final Map<Material, Map<String, ItemBuilder>> texturedItems,
            boolean useSelect, boolean includeBothModes) {
        for (final Map.Entry<Material, Map<String, ItemBuilder>> texturedItemsEntry : texturedItems.entrySet()) {
            final Material material = texturedItemsEntry.getKey();
            final List<ItemBuilder> items = new ArrayList<>(texturedItemsEntry.getValue().values());

            final VanillaItemDefinitionGenerator generator = new VanillaItemDefinitionGenerator(
                    material, items, useSelect, includeBothModes);
            ResourcePack.writeStringToVirtual("assets/minecraft/items", generator.getFileName(),
                    generator.toJSON().toString());
        }
    }

    private void generateModelDefinitions(final Map<Material, Map<String, ItemBuilder>> texturedItems) {
        for (final Map.Entry<Material, Map<String, ItemBuilder>> materialEntry : texturedItems.entrySet()) {
            for (final Map.Entry<String, ItemBuilder> entry : materialEntry.getValue().entrySet()) {
                Material key = materialEntry.getKey();

                String itemId = entry.getKey();
                ItemBuilder texturedItem = entry.getValue();
                OraxenMeta oraxenMeta = texturedItem.getOraxenMeta();
                if (oraxenMeta.hasPackInfos()) {
                    // Generate the main item model definition
                    final ModelDefinitionGenerator modelDefinitionGenerator = new ModelDefinitionGenerator(oraxenMeta,
                            key);
                    ResourcePack.writeStringToVirtual("assets/oraxen/items/", itemId + ".json",
                            modelDefinitionGenerator.toJSON().toString());

                    // Generate additional model definitions from Pack.models
                    // These are registered as oraxen:<itemId>/<key> -> modelPath
                    if (oraxenMeta.hasAdditionalModels()) {
                        for (Map.Entry<String, String> modelEntry : oraxenMeta.getAdditionalModels().entrySet()) {
                            String modelKey = modelEntry.getKey();
                            String modelPath = modelEntry.getValue();
                            String additionalDefinitionId = itemId + "/" + modelKey;
                            String additionalDefinition = createSimpleModelDefinition(modelPath);
                            ResourcePack.writeStringToVirtual("assets/oraxen/items/", additionalDefinitionId + ".json",
                                    additionalDefinition);
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates a simple model definition JSON for additional models.
     * Used by Pack.models to register model aliases.
     */
    private String createSimpleModelDefinition(String modelPath) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonObject model = new com.google.gson.JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", modelPath);
        root.add("model", model);
        return root.toString();
    }

    private Map<Material, Map<String, ItemBuilder>> filterForItemModel(
            Map<Material, Map<String, ItemBuilder>> texturedItems) {
        Map<Material, Map<String, ItemBuilder>> filtered = new HashMap<>();
        for (Map.Entry<Material, Map<String, ItemBuilder>> materialEntry : texturedItems.entrySet()) {
            Map<String, ItemBuilder> filteredItems = new LinkedHashMap<>();
            for (Map.Entry<String, ItemBuilder> entry : materialEntry.getValue().entrySet()) {
                OraxenMeta meta = entry.getValue().getOraxenMeta();
                if (meta != null && !meta.isExcludedFromItemModel()) {
                    filteredItems.put(entry.getKey(), entry.getValue());
                }
            }
            if (!filteredItems.isEmpty()) {
                filtered.put(materialEntry.getKey(), filteredItems);
            }
        }
        return filtered;
    }

    private Map<Material, Map<String, ItemBuilder>> filterForModelData(
            Map<Material, Map<String, ItemBuilder>> texturedItems) {
        Map<Material, Map<String, ItemBuilder>> filtered = new HashMap<>();
        for (Map.Entry<Material, Map<String, ItemBuilder>> materialEntry : texturedItems.entrySet()) {
            Map<String, ItemBuilder> filteredItems = new LinkedHashMap<>();
            for (Map.Entry<String, ItemBuilder> entry : materialEntry.getValue().entrySet()) {
                OraxenMeta meta = entry.getValue().getOraxenMeta();
                if (meta != null && !meta.isExcludedFromPredicates()) {
                    filteredItems.put(entry.getKey(), entry.getValue());
                }
            }
            if (!filteredItems.isEmpty()) {
                filtered.put(materialEntry.getKey(), filteredItems);
            }
        }
        return filtered;
    }

}
