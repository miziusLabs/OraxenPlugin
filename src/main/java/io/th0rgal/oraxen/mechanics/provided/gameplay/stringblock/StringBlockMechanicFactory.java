package io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock;

import com.google.gson.JsonObject;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.mechanics.Mechanic;
import io.th0rgal.oraxen.mechanics.MechanicConfigProperty;
import io.th0rgal.oraxen.mechanics.MechanicFactory;
import io.th0rgal.oraxen.mechanics.MechanicsManager;
import io.th0rgal.oraxen.mechanics.provided.gameplay.block.BlockMechanicFactory;
import io.th0rgal.oraxen.mechanics.provided.gameplay.block.BlockStateCodec;
import io.th0rgal.oraxen.mechanics.provided.gameplay.block.CustomBlockService;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.sapling.SaplingListener;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.sapling.SaplingTask;
import io.th0rgal.oraxen.utils.BlockHelpers;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.utils.blocksounds.BlockSounds;
import io.th0rgal.oraxen.utils.logs.Logs;
import org.apache.commons.lang3.Range;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class StringBlockMechanicFactory extends MechanicFactory {

    // Repopulated on reload (a region/command thread on Folia) while every block
    // event handler reads it from other region threads.
    public static final Map<Integer, StringBlockMechanic> BLOCK_PER_VARIATION = new ConcurrentHashMap<>();
    private static StringBlockMechanicFactory instance;
    public final List<String> toolTypes;
    private boolean sapling;
    private static SaplingTask saplingTask;
    private final int saplingGrowthCheckDelay;
    public final boolean customSounds;
    private final boolean registerListeners;
    private boolean enabled;
    private final Map<Integer, String> models = new ConcurrentHashMap<>();

    public StringBlockMechanicFactory(ConfigurationSection section) {
        this(section, true);
    }

    public StringBlockMechanicFactory(ConfigurationSection section, boolean registerListeners) {
        super(section);
        instance = this;
        BLOCK_PER_VARIATION.clear();
        CustomBlockService.clearStringBlocks();
        models.clear();
        toolTypes = section.getStringList("tool_types");
        saplingGrowthCheckDelay = section.getInt("sapling_growth_check_delay");
        sapling = false;
        customSounds = areCustomSoundsEnabled();
        this.registerListeners = registerListeners;
        enabled = false;
    }

    public static JsonObject getModelJson(String modelName) {
        JsonObject content = new JsonObject();
        content.addProperty("model", modelName);
        return content;
    }

    public static String getBlockstateVariantName(int id) {
        return BlockStateCodec.formatTripwireVariantKey(id);
    }

    @org.jetbrains.annotations.Nullable
    public static StringBlockMechanic getBlockMechanic(int customVariation) {
        return CustomBlockService.getStringBlock(customVariation);
    }

    public static boolean isEnabled() {
        return instance != null && instance.enabled && MechanicsManager.isMechanicEnabled("block");
    }

    public static boolean areCustomSoundsEnabled() {
        OraxenPlugin plugin = OraxenPlugin.get();
        if (plugin == null || plugin.getConfigsManager() == null) return true;

        ConfigurationSection customSoundsSection = plugin.getConfigsManager().getMechanics()
                .getConfigurationSection("custom_block_sounds");
        return BlockSounds.isStringBlockSoundEnabled(customSoundsSection);
    }

    public static StringBlockMechanicFactory getInstance() {
        return instance;
    }

    public static void clearInstance(StringBlockMechanicFactory factory) {
        if (instance != factory) return;
        instance = null;
        BLOCK_PER_VARIATION.clear();
        CustomBlockService.clearStringBlocks();
    }

    public static @NotNull Map<Integer, String> getModels() {
        StringBlockMechanicFactory factory = instance;
        return factory != null && factory.enabled ? Map.copyOf(factory.models) : Map.of();
    }

    /**
     * Attempts to set the block directly to the model and texture of an Oraxen item.
     *
     * @param block  The block to update.
     * @param itemId The Oraxen item ID.
     */
    public static void setBlockModel(Block block, String itemId) {
        Location location = block.getLocation();
        if (!Bukkit.isOwnedByCurrentRegion(location)) {
            SchedulerUtil.runAtLocation(location, () -> setBlockModel(block, itemId));
            return;
        }

        StringBlockMechanic mechanic = getInstance().getMechanic(itemId);
        if (mechanic == null) return;

        Block base = StringBlockState.base(block);
        Block target = base != null ? base : block;
        if (target.getType() == Material.TRIPWIRE) return;
        if (base == null && !canUseCarrierPosition(target)) return;

        boolean tall = mechanic.isTall();
        if (tall) {
            Block upper = target.getRelative(BlockFace.UP);
            if (upper.getY() >= target.getWorld().getMaxHeight()) return;
            boolean ownUpper = base != null && target.equals(StringBlockState.base(upper));
            if (!ownUpper && (upper.getType() == Material.TRIPWIRE || StringBlockState.hasMarker(upper) || !canUseCarrierPosition(upper)))
                return;
        }
        if (!tall && base != null) StringBlockState.clearUpper(target);
        StringBlockState.place(target, mechanic.getCustomVariation(), tall);
    }

    private static boolean canUseCarrierPosition(Block block) {
        return BlockHelpers.isReplaceable(block.getType()) || block.getType() == StringBlockState.MATERIAL;
    }

    private void writeItemModels() {
        if (instance != this || !enabled) return;
        for (Map.Entry<Integer, String> entry : models.entrySet()) {
            JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            model.addProperty("model", entry.getValue());
            JsonObject definition = new JsonObject();
            definition.add("model", model);
            OraxenPlugin.get().getResourcePack().writeStringToVirtual(
                    "assets/oraxen_string_blocks/items", entry.getKey() + ".json", definition.toString());
        }

        if (!models.isEmpty()) {
            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", "minecraft:item/structure_void");
            JsonObject fallback = new JsonObject();
            fallback.addProperty("parent", "minecraft:item/generated");
            fallback.add("textures", textures);
            OraxenPlugin.get().getResourcePack().writeStringToVirtual(
                    "assets/oraxen_string_blocks/models", "legacy_fallback.json", fallback.toString());
        }
    }

    private void enable() {
        if (enabled) return;
        enabled = true;

        // This modifier runs after all items have been parsed, just before zipping the pack.
        OraxenPlugin.get().getResourcePack().addModifiers(getMechanicID(), packFolder -> writeItemModels());
        if (!registerListeners) return;

        MechanicsManager.registerListeners(OraxenPlugin.get(), getMechanicID(), new StringBlockMechanicListener(), new SaplingListener());
        if (customSounds) MechanicsManager.registerListeners(OraxenPlugin.get(), getMechanicID(), new StringBlockSoundListener());
    }

    @Override
    public Mechanic parse(ConfigurationSection itemMechanicConfiguration) {
        enable();
        StringBlockMechanic mechanic = new StringBlockMechanic(this, itemMechanicConfiguration);
        if (!Range.between(1, 127).contains(mechanic.getCustomVariation())) {
            Logs.logError("The custom_variation of " + mechanic.getItemID() + " is " + mechanic.getCustomVariation() + ", but must be between 1 and 127!");
            Logs.logWarning("The item has failed to build for now to prevent bugs and issues.");
            return null;
        }

        String baseModel = Objects.requireNonNullElse(mechanic.getModel(itemMechanicConfiguration.getParent().getParent()), "minecraft:block/barrier");
        models.put(mechanic.getCustomVariation(), baseModel);
        StringBlockMechanic previous = CustomBlockService.registerStringBlock(mechanic.getCustomVariation(), mechanic);
        BlockMechanicFactory.logDuplicateVariation(previous, mechanic, mechanic.getCustomVariation());

        // Register stackable variations. Each accepted stack level maps back to the parent mechanic.
        for (StringBlockMechanic.StackVariation sv : mechanic.getStackVariations()) {
            if (!Range.between(1, 127).contains(sv.customVariation())) {
                Logs.logError("Stackable custom_variation " + sv.customVariation() + " of " + mechanic.getItemID() + " must be between 1 and 127!");
                continue;
            }
            StringBlockMechanic existing = BLOCK_PER_VARIATION.get(sv.customVariation());
            if (existing != null) {
                Logs.logError("Stackable custom_variation " + sv.customVariation() + " of " + mechanic.getItemID() + " is already used by " + existing.getItemID() + "!");
                continue;
            }
            String stackModel = sv.model() != null ? sv.model() : baseModel;
            models.put(sv.customVariation(), stackModel);
            CustomBlockService.registerStringBlock(sv.customVariation(), mechanic);
        }

        addToImplemented(mechanic);
        return mechanic;
    }

    @Override
    public StringBlockMechanic getMechanic(String itemID) {
        return (StringBlockMechanic) super.getMechanic(itemID);
    }

    @Override
    public StringBlockMechanic getMechanic(ItemStack itemStack) {
        return (StringBlockMechanic) super.getMechanic(itemStack);
    }

    /**
     * Legacy codec used by migration and compatibility callers. Live string blocks do not
     * read their identity from tripwire states.
     */
    public static int getCode(final Tripwire blockData) {
        return BlockStateCodec.decodeTripwireVariation(blockData);
    }

    /**
     * Legacy codec used by migration and compatibility callers. Live string blocks use
     * STRUCTURE_VOID carriers with chunk-persistent identity instead.
     *
     * @param code The legacy variation id.
     */
    public static BlockData createTripwireData(final int code) {
        return BlockStateCodec.encodeTripwireVariation(code);
    }

    /**
     * Legacy codec used by migration and compatibility callers.
     *
     * @param itemID The id of an item implementing StringBlockMechanic
     */
    public BlockData createTripwireData(String itemID) {
        return createTripwireData(((StringBlockMechanic) getInstance().getMechanic(itemID)).getCustomVariation());
    }

    public void registerSaplingMechanic() {
        if (sapling) return;
        if (saplingTask != null) saplingTask.cancel();

        saplingTask = new SaplingTask(saplingGrowthCheckDelay);
        MechanicsManager.registerTask(getMechanicID(), saplingTask.start(0, saplingGrowthCheckDelay));
        sapling = true;
    }

    @Override
    public @Nullable String getMechanicCategory() {
        return "gameplay";
    }

    @Override
    public @Nullable String getMechanicDescription() {
        return "Creates pass-through custom blocks (up to 127 variations)";
    }

    @Override
    public @NotNull List<MechanicConfigProperty> getConfigSchema() {
        return List.of(
                MechanicConfigProperty.integer("custom_variation", "Unique variation ID (1-127)", 1, 1, 127),
                MechanicConfigProperty.string("model", "Block model path"),
                MechanicConfigProperty.decimal("hardness", "Block break hardness", 1.0, 0.0),
                MechanicConfigProperty.integer("light", "Light level emitted (0-15)", 0, 0, 15),
                MechanicConfigProperty.bool("blast_resistant", "Whether block resists explosions", false),
                MechanicConfigProperty.bool("immovable", "Whether block resists pistons", false),
                MechanicConfigProperty.object("drop", "Drop configuration when broken", Map.of(
                        "silktouch", MechanicConfigProperty.bool("silktouch", "Require silk touch", false),
                        "loots", MechanicConfigProperty.list("loots", "List of loot entries")
                )),
                MechanicConfigProperty.object("sapling", "Sapling growth configuration", Map.ofEntries(
                        Map.entry("canGrowNaturally", MechanicConfigProperty.bool("canGrowNaturally", "Whether the sapling grows over time", true)),
                        Map.entry("naturalGrowthTime", MechanicConfigProperty.integer("naturalGrowthTime", "Ticks until the sapling grows", 6000, 0)),
                        Map.entry("canGrowFromBoneMeal", MechanicConfigProperty.bool("canGrowFromBoneMeal", "Whether bone meal speeds up growth", true)),
                        Map.entry("boneMealGrowthSpeedup", MechanicConfigProperty.integer("boneMealGrowthSpeedup", "Ticks of growth each bone meal adds", 1200, 0)),
                        Map.entry("growSound", MechanicConfigProperty.string("growSound", "Sound played when the sapling grows")),
                        Map.entry("minLightLevel", MechanicConfigProperty.integer("minLightLevel", "Minimum light level to grow", 0, 0, 15)),
                        Map.entry("requiresWaterSource", MechanicConfigProperty.bool("requiresWaterSource", "Whether the sapling needs water below it", false)),
                        Map.entry("schematicName", MechanicConfigProperty.string("schematicName", "Schematic pasted when the sapling grows")),
                        Map.entry("replaceBlocks", MechanicConfigProperty.bool("replaceBlocks", "Whether the schematic may replace existing blocks", false)),
                        Map.entry("shouldCopyBiomes", MechanicConfigProperty.bool("shouldCopyBiomes", "Whether the schematic pastes its biomes", false)),
                        Map.entry("shouldCopyEntities", MechanicConfigProperty.bool("shouldCopyEntities", "Whether the schematic pastes its entities", false))
                )),
                MechanicConfigProperty.list("stackable", "List of stack variations (custom_variation + model per level)")
        );
    }
}
