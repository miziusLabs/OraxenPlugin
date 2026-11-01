package io.th0rgal.oraxen.nms.handler.java21;

import com.google.gson.JsonParser;
import com.google.common.base.Function;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Server identities live in the block registry. Only outgoing packets use note block appearances. */
public final class NativeCustomBlocks {
    private static final Map<String, CustomBlock> blocks = new ConcurrentHashMap<>();
    private static final Map<Block, BlockState> appearances = new ConcurrentHashMap<>();
    private static final Map<String, Field> fields = new ConcurrentHashMap<>();
    private static volatile boolean sealed;
    private static final int vanillaStateCount = Block.BLOCK_STATE_REGISTRY.size();

    private NativeCustomBlocks() {
    }

    /** Called during plugin load, before the server opens world region files. */
    public static void bootstrap(Path cache) throws IOException {
        if (!Files.isRegularFile(cache)) return;
        try (var reader = Files.newBufferedReader(cache)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            for (var element : root.getAsJsonArray("blocks")) {
                var entry = element.getAsJsonObject();
                register(entry.get("key").getAsString(),
                        Bukkit.createBlockData(entry.get("appearance").getAsString()),
                        entry.get("light").getAsInt());
            }
        }
    }

    public static synchronized void register(String key, BlockData appearance, int light) {
        if (!key.matches("oraxen:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid native block key " + key);
        if (light < 0 || light > 15) throw new IllegalArgumentException("Block light must be between 0 and 15");
        BlockState clientState = ((CraftBlockData) appearance).getState();
        if (clientState.getBlock() != Blocks.NOTE_BLOCK) throw new IllegalArgumentException("Expected vanilla note block appearance");
        CustomBlock previous = blocks.get(key);
        if (previous != null) {
            if (previous.light != light) {
                if (sealed) throw new IllegalStateException("Changing native block light requires a restart for " + key);
                try {
                    Field emission = field(BlockBehaviour.BlockStateBase.class, "lightEmission");
                    for (BlockState state : previous.getStateDefinition().getPossibleStates())
                        emission.setInt(state, light);
                    previous.light = light;
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Cannot update native block light for " + key, exception);
                }
            }
            appearances.put(previous, clientState);
            return;
        }
        if (sealed) throw new IllegalStateException("Registering a new native custom block requires a server restart for " + key);
        // Registry writes are confined to bootstrap/configuration. Never run this from a region task.
        var registry = (MappedRegistry<Block>) BuiltInRegistries.BLOCK;
        try {
            Field frozen = field(MappedRegistry.class, "frozen");
            Field intrusive = field(MappedRegistry.class, "unregisteredIntrusiveHolders");
            boolean wasFrozen = frozen.getBoolean(registry);
            Object oldIntrusive = intrusive.get(registry);
            frozen.setBoolean(registry, false);
            if (oldIntrusive == null) intrusive.set(registry, new IdentityHashMap<>());
            try {
                ResourceKey<Block> resourceKey = resourceKey(key);
                if (BuiltInRegistries.BLOCK.containsKey(resourceKey))
                    throw new IllegalStateException("Native block key is already registered " + key);
                CustomBlock block = new CustomBlock(key, light,
                        BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK).setId(resourceKey)
                                .lightLevel(state -> light).noLootTable());
                Registry.register(BuiltInRegistries.BLOCK, resourceKey, block);
                // The intrusive holder already owns its value and register binds its key.
                // Bind this holder's tags without freezing the already tagged global registry again.
                field(Holder.Reference.class, "tags").set(block.builtInRegistryHolder(), Set.of());
                ((Map<?, ?>) field(MappedRegistry.class, "temporaryUnfrozenMap").get(registry)).values().remove(block);
                installBukkitMappings(block);
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    Block.BLOCK_STATE_REGISTRY.add(state);
                    state.initCache();
                }
                appearances.put(block, clientState);
                blocks.put(key, block);
            } finally {
                intrusive.set(registry, oldIntrusive);
                frozen.setBoolean(registry, wasFrozen);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot register native block " + key, exception);
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
        ((Map<Class<?>, Function<BlockState, CraftBlockData>>) field(CraftBlockData.class, "MAP").get(null))
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

    public static void sealRegistrations() {
        sealed = true;
    }

    static BlockData data(String key) {
        CustomBlock block = blocks.get(key);
        return block == null ? null : CraftBlockData.fromData(block.defaultBlockState());
    }

    static String key(BlockData data) {
        return data instanceof CraftBlockData craft && craft.getState().getBlock() instanceof CustomBlock block ? block.key : null;
    }

    static boolean enabled() {
        return !blocks.isEmpty();
    }

    static int vanillaBits() {
        return 32 - Integer.numberOfLeadingZeros(vanillaStateCount - 1);
    }

    static BlockState project(BlockState state) {
        BlockState appearance = appearances.get(state.getBlock());
        if (appearance != null) return appearance;
        // All real vanilla notes share the reserved vanilla model, while their server state stays intact.
        return state.getBlock() == Blocks.NOTE_BLOCK
                ? Blocks.NOTE_BLOCK.defaultBlockState().setValue(NoteBlock.NOTE, state.getValue(NoteBlock.NOTE)) : state;
    }

    static int project(int stateId) {
        return Block.getId(project(Block.stateById(stateId)));
    }

    private static final class NativeBlockData extends CraftBlockData {
        private NativeBlockData(BlockState state) {
            super(state);
        }
    }

    private static final class CustomBlock extends Block {
        private final String key;
        private int light;

        private CustomBlock(String key, int light, BlockBehaviour.Properties properties) {
            super(properties);
            this.key = key;
            this.light = light;
        }
    }
}
