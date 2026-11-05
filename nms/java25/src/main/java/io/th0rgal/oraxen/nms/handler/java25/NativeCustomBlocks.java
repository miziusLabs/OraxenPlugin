package io.th0rgal.oraxen.nms.handler.java25;

import com.google.common.base.Function;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.th0rgal.oraxen.mechanics.provided.gameplay.block.NativeBlockRegistry;
import io.th0rgal.oraxen.nms.NMSHandler;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Stable server block identities backed by slots registered before worlds load. */
public final class NativeCustomBlocks {
    private static final int slotCount = NativeBlockRegistry.SLOT_COUNT;
    private static final CustomBlock[] slotBlocks = new CustomBlock[slotCount];
    private static volatile NativeMappings mappings = NativeMappings.empty();
    private static final Map<String, Field> fields = new ConcurrentHashMap<>();
    private static volatile boolean bootstrapped;
    private static final int vanillaStateCount = Block.BLOCK_STATE_REGISTRY.size();

    private record NativeMappings(Map<String, CustomBlock> blocks, Map<String, Integer> slotsByKey,
                                  Map<Block, BlockState> appearances, String[] keysBySlot, int[] lightBySlot) {
        private static NativeMappings empty() {
            return new NativeMappings(Map.of(), Map.of(), Map.of(), new String[slotCount], new int[slotCount]);
        }
    }

    private NativeCustomBlocks() {
    }

    /** Called during plugin load, before the server opens world region files. */
    public static synchronized void bootstrap(Path cache) throws IOException {
        if (bootstrapped) return;

        Map<Integer, JsonObject> cachedBlocks = new HashMap<>();
        if (Files.isRegularFile(cache)) {
            try (var reader = Files.newBufferedReader(cache)) {
                var root = JsonParser.parseReader(reader).getAsJsonObject();
                for (var element : root.getAsJsonArray("blocks")) {
                    JsonObject entry = element.getAsJsonObject();
                    if (!entry.has("variation"))
                        throw new IllegalStateException("Native block cache entry has no variation");
                    int slot = entry.get("variation").getAsInt();
                    if (slot < 0 || slot >= slotCount)
                        throw new IllegalStateException("Invalid native block slot " + slot);
                    if (cachedBlocks.putIfAbsent(slot, entry) != null)
                        throw new IllegalStateException("Duplicate native block slot " + slot);
                }
            }
        }

        BlockData defaultAppearance = CraftBlockData.createData(Blocks.NOTE_BLOCK.defaultBlockState());
        Map<String, CustomBlock> bootstrappedBlocks = new HashMap<>();
        Map<String, Integer> bootstrappedSlots = new HashMap<>();
        Map<Block, BlockState> bootstrappedAppearances = new IdentityHashMap<>();
        String[] bootstrappedKeys = new String[slotCount];
        int[] bootstrappedLights = new int[slotCount];
        for (int slot = 0; slot < slotCount; slot++) {
            JsonObject entry = cachedBlocks.get(slot);
            String physicalKey = physicalKey(entry, slot);
            BlockData appearance = entry != null && entry.has("appearance")
                    ? Bukkit.createBlockData(entry.get("appearance").getAsString()) : defaultAppearance;
            int light = entry != null && entry.has("light") ? entry.get("light").getAsInt() : 0;
            if (light < 0 || light > 15)
                throw new IllegalStateException("Invalid native block light level " + light + " for slot " + slot);
            if (!(appearance instanceof CraftBlockData craftAppearance)
                    || craftAppearance.getState().getBlock() != Blocks.NOTE_BLOCK)
                throw new IllegalStateException("Invalid native block appearance for slot " + slot);
            CustomBlock block = registerPhysicalBlock(physicalKey, slot);
            slotBlocks[slot] = block;
            bootstrappedLights[slot] = light;

            if (entry != null && entry.has("key")) {
                String key = entry.get("key").getAsString();
                validateBinding(key, slot, bootstrappedKeys, bootstrappedSlots);
                bootstrappedKeys[slot] = key;
                bootstrappedSlots.put(key, slot);
                bootstrappedBlocks.put(key, block);
                bootstrappedAppearances.put(block, craftAppearance.getState());
            }
        }
        mappings = new NativeMappings(Map.copyOf(bootstrappedBlocks), Map.copyOf(bootstrappedSlots),
                Map.copyOf(bootstrappedAppearances), bootstrappedKeys, bootstrappedLights);
        bootstrapped = true;
    }

    private static String physicalKey(JsonObject entry, int slot) {
        if (entry != null && entry.has("blockId")) return entry.get("blockId").getAsString();
        // Preserve registry names already written to chunk palettes by older Oraxen builds.
        if (entry != null && entry.has("key")) return entry.get("key").getAsString();
        return "oraxen:native_block/slot_" + slot;
    }

    /** Update a logical block mapping without changing the frozen server registries. */
    public static synchronized void register(String key, int slot, BlockData appearance, int light) {
        registerAll(List.of(new NMSHandler.CustomBlockRegistration(key, slot, appearance, light)));
    }

    /** Publish a complete reload in one volatile mapping swap. */
    public static synchronized void registerAll(List<NMSHandler.CustomBlockRegistration> registrations) {
        if (!bootstrapped) throw new IllegalStateException("Native block slots were not bootstrapped before world load");
        if (registrations.isEmpty()) return;

        NativeMappings current = mappings;
        Map<String, CustomBlock> nextBlocks = new HashMap<>(current.blocks());
        Map<String, Integer> nextSlots = new HashMap<>(current.slotsByKey());
        Map<Block, BlockState> nextAppearances = new IdentityHashMap<>(current.appearances());
        String[] nextKeys = current.keysBySlot().clone();
        int[] nextLights = current.lightBySlot().clone();
        Set<String> seenKeys = new java.util.HashSet<>();
        Set<Integer> seenSlots = new java.util.HashSet<>();

        for (NMSHandler.CustomBlockRegistration registration : registrations) {
            String key = registration.key();
            int slot = registration.slot();
            int light = registration.light();
            BlockData appearance = registration.appearance();
            validateRegistration(key, slot, light, appearance);
            if (!seenKeys.add(key)) throw new IllegalArgumentException("Duplicate native block registration " + key);
            if (!seenSlots.add(slot)) throw new IllegalArgumentException("Duplicate native block slot registration " + slot);
            validateBinding(key, slot, nextKeys, nextSlots);

            CustomBlock block = slotBlocks[slot];
            BlockState clientState = ((CraftBlockData) appearance).getState();
            nextKeys[slot] = key;
            nextSlots.put(key, slot);
            nextBlocks.put(key, block);
            nextAppearances.put(block, clientState);
            nextLights[slot] = light;
        }

        mappings = new NativeMappings(Map.copyOf(nextBlocks), Map.copyOf(nextSlots),
                Map.copyOf(nextAppearances), nextKeys, nextLights);
    }

    private static void validateRegistration(String key, int slot, int light, BlockData appearance) {
        if (!key.matches("oraxen:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid native block key " + key);
        if (slot < 0 || slot >= slotCount) throw new IllegalArgumentException("Invalid native block slot " + slot);
        if (light < 0 || light > 15) throw new IllegalArgumentException("Block light must be between 0 and 15");
        if (!(appearance instanceof CraftBlockData craftAppearance))
            throw new IllegalArgumentException("Expected CraftBlockData for native block appearance");
        if (craftAppearance.getState().getBlock() != Blocks.NOTE_BLOCK)
            throw new IllegalArgumentException("Expected vanilla note block appearance");
        if (slotBlocks[slot] == null) throw new IllegalStateException("Native block slot is unavailable " + slot);
    }

    private static void validateBinding(String key, int slot, String[] keysBySlot, Map<String, Integer> slotsByKey) {
        if (!key.matches("oraxen:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid native block key " + key);
        String previousKey = keysBySlot[slot];
        if (previousKey != null && !previousKey.equals(key))
            throw new IllegalStateException("Native block slot " + slot + " is already assigned to " + previousKey);
        Integer previousSlot = slotsByKey.get(key);
        if (previousSlot != null && previousSlot != slot)
            throw new IllegalStateException("Native block identity " + key + " is already assigned to slot " + previousSlot);
    }

    @SuppressWarnings("unchecked")
    private static CustomBlock registerPhysicalBlock(String key, int slot) throws IOException {
        if (!key.matches("oraxen:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid native block registry key " + key);
        var registry = (MappedRegistry<Block>) BuiltInRegistries.BLOCK;
        try {
            Field frozen = field(MappedRegistry.class, "frozen");
            Field intrusive = field(MappedRegistry.class, "unregisteredIntrusiveHolders");
            boolean wasFrozen = frozen.getBoolean(registry);
            Object oldIntrusive = intrusive.get(registry);
            frozen.setBoolean(registry, false);
            if (oldIntrusive == null) intrusive.set(registry, new IdentityHashMap<>());
            try {
                ResourceKey<Block> keyResource = resourceKey(key);
                if (BuiltInRegistries.BLOCK.containsKey(keyResource))
                    throw new IllegalStateException("Native block registry key is already registered " + key);
                CustomBlock block = new CustomBlock(slot,
                        BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK).setId(keyResource)
                                .lightLevel(state -> state.getValue(CustomBlock.lightLevelProperty)).noLootTable());
                Registry.register(BuiltInRegistries.BLOCK, keyResource, block);
                field(Holder.Reference.class, "tags").set(block.builtInRegistryHolder(), Set.of());
                ((Map<?, ?>) field(MappedRegistry.class, "temporaryUnfrozenMap").get(registry)).values().remove(block);
                installBukkitMappings(block);
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    Block.BLOCK_STATE_REGISTRY.add(state);
                    state.initCache();
                }
                return block;
            } finally {
                intrusive.set(registry, oldIntrusive);
                frozen.setBoolean(registry, wasFrozen);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IOException("Cannot register native block slot " + slot + " as " + key, exception);
        }
    }

    /** Copy vanilla note-block tags after the server has loaded built-in block tags. */
    public static void finishPhysicalBlockRegistration() {
        final Set<?> noteBlockTags = Blocks.NOTE_BLOCK.builtInRegistryHolder().tags()
                .collect(Collectors.toUnmodifiableSet());
        try {
            final Field tags = field(Holder.Reference.class, "tags");
            for (final CustomBlock block : slotBlocks) {
                if (block != null) tags.set(block.builtInRegistryHolder(), noteBlockTags);
            }
        } catch (final ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not copy vanilla note-block tags to native blocks", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static ResourceKey<Block> resourceKey(String value) throws ReflectiveOperationException {
        // ResourceLocation became Identifier in 1.21.11. Avoid its descriptor in shared Java 21 code.
        String identifierName = "net.minecraft.resources.Identifier";
        Class<?> identifier;
        try {
            identifier = Class.forName(identifierName);
        } catch (ClassNotFoundException ignored) {
            identifier = Class.forName("net.minecraft.resources.ResourceLocation");
        }
        Object id = identifier.getMethod("parse", String.class).invoke(null, value);
        Method create = ResourceKey.class.getMethod("create", ResourceKey.class, identifier);
        return (ResourceKey<Block>) create.invoke(null, Registries.BLOCK, id);
    }

    @SuppressWarnings("unchecked")
    private static void installBukkitMappings(Block block) throws ReflectiveOperationException {
        ((Map<Block, Material>) field(CraftMagicNumbers.class, "BLOCK_MATERIAL").get(null)).put(block, Material.NOTE_BLOCK);
        ((Map<Class<?>, Function<BlockState, CraftBlockData>>) field(CraftBlockData.class, "INSTANCE_CREATOR").get(null))
                .put(CustomBlock.class, NativeBlockData::new);
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        String key = type.getName() + "." + name;
        Field cached = fields.get(key);
        if (cached != null) return cached;
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        fields.put(key, field);
        return field;
    }

    static BlockData data(String key) {
        NativeMappings current = mappings;
        CustomBlock block = current.blocks().get(key);
        return block == null ? null : CraftBlockData.createData(
                block.defaultBlockState().setValue(CustomBlock.lightLevelProperty, current.lightBySlot()[block.slot]));
    }

    static String key(BlockData data) {
        if (!(data instanceof CraftBlockData craft) || !(craft.getState().getBlock() instanceof CustomBlock block)) return null;
        return mappings.keysBySlot()[block.slot];
    }

    static void finishReload() {
        // Light changes are represented by block-state transitions during the region scan.
    }

    static boolean enabled() {
        return !mappings.blocks().isEmpty();
    }

    static boolean requiresPaletteRepack() {
        return bootstrapped && NativeBlockRegistry.blockPaletteNeedsRepack(
                vanillaStateCount, Block.BLOCK_STATE_REGISTRY.size());
    }

    static int vanillaBits() {
        return 32 - Integer.numberOfLeadingZeros(vanillaStateCount - 1);
    }

    static BlockState project(BlockState state) {
        NativeMappings current = mappings;
        Block block = state.getBlock();
        BlockState appearance = current.appearances().get(block);
        if (appearance != null) return appearance;
        if (block instanceof CustomBlock) return Blocks.NOTE_BLOCK.defaultBlockState();
        // All real vanilla notes share the reserved vanilla model, while their server state stays intact.
        return block == Blocks.NOTE_BLOCK
                ? Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, state.getValue(NoteBlock.NOTE)) : state;
    }

    static int project(int stateId) {
        return Block.getId(project(Block.stateById(stateId)));
    }

    static BlockState projectPalette(BlockState state) {
        NativeMappings current = mappings;
        Block block = state.getBlock();
        BlockState appearance = current.appearances().get(block);
        if (appearance != null) return appearance;
        return block instanceof CustomBlock ? Blocks.NOTE_BLOCK.defaultBlockState() : state;
    }

    static int projectPalette(int stateId) {
        return Block.getId(projectPalette(Block.stateById(stateId)));
    }

    private static final class NativeBlockData extends CraftBlockData {
        private NativeBlockData(BlockState state) {
            super(state);
        }
    }

    private static final class CustomBlock extends Block {
        private static final IntegerProperty lightLevelProperty = IntegerProperty.create("light_level", 0, 15);
        private final int slot;

        private CustomBlock(int slot, BlockBehaviour.Properties properties) {
            super(properties);
            this.slot = slot;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(lightLevelProperty);
        }
    }
}
