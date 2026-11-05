package io.th0rgal.oraxen.mechanics.provided.gameplay.furniture;

import io.th0rgal.oraxen.utils.SchedulerUtil;
import io.th0rgal.oraxen.api.OraxenFurniture;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Tracks marked furniture entities without scanning region-owned world lists. */
public final class FurnitureEntityIndex implements Listener {

    private static final ConcurrentMap<UUID, Entity> entities = new ConcurrentHashMap<>();
    private static final Set<ChunkKey> indexedChunks = ConcurrentHashMap.newKeySet();
    private static volatile NamespacedKey furnitureKey;
    private static boolean registered;

    private final Plugin plugin;

    private FurnitureEntityIndex(Plugin plugin) {
        this.plugin = plugin;
        furnitureKey = new NamespacedKey(plugin, "furniture");
    }

    public static synchronized void register(Plugin plugin) {
        if (registered) return;
        plugin.getServer().getPluginManager().registerEvents(new FurnitureEntityIndex(plugin), plugin);
        registered = true;
    }

    public static void track(Entity entity) {
        if (entity == null) return;
        NamespacedKey key = furnitureKey;
        if (key == null) return;
        if (entity.getPersistentDataContainer().has(key, PersistentDataType.STRING))
            entities.put(entity.getUniqueId(), entity);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        event.getEntities().forEach(this::trackMarkedEntity);
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        event.getEntities().forEach(entity -> entities.remove(entity.getUniqueId(), entity));
    }

    @EventHandler
    public void onEntityAdd(EntityAddToWorldEvent event) {
        trackMarkedEntity(event.getEntity());
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        Entity entity = event.getEntity();
        entities.remove(entity.getUniqueId(), entity);
    }

    @EventHandler
    public void onPlayerChunkLoad(PlayerChunkLoadEvent event) {
        Chunk sentChunk = event.getChunk();
        World world = sentChunk.getWorld();
        int chunkX = sentChunk.getX();
        int chunkZ = sentChunk.getZ();
        Location location = new Location(world, chunkX << 4, world.getMinHeight(), chunkZ << 4);
        SchedulerUtil.runAtLocation(plugin, location, () -> {
            if (!world.isChunkLoaded(chunkX, chunkZ)) return;
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            if (!chunk.isEntitiesLoaded() || !indexedChunks.add(new ChunkKey(world.getUID(), chunkX, chunkZ))) return;

            for (Entity entity : chunk.getEntities()) {
                trackMarkedEntity(entity);
                if (FurnitureFactory.getInstance() != null && OraxenFurniture.isBaseEntity(entity)) {
                    SchedulerUtil.runForEntity(plugin, entity, () -> {
                        if (entity.isValid() && OraxenFurniture.isBaseEntity(entity))
                            FurnitureFactory.registerTextEntity(entity, true);
                    }, () -> entities.remove(entity.getUniqueId(), entity));
                }
            }
        });
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        indexedChunks.remove(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    private void trackMarkedEntity(Entity entity) {
        NamespacedKey key = furnitureKey;
        if (key != null && entity.getPersistentDataContainer().has(key, PersistentDataType.STRING))
            entities.put(entity.getUniqueId(), entity);
    }

    public static void forEachLoaded(Consumer<Entity> action) {
        for (Entity entity : List.copyOf(entities.values())) {
            SchedulerUtil.runForEntity(entity, () -> {
                if (!entity.isValid()) {
                    entities.remove(entity.getUniqueId(), entity);
                    return;
                }
                action.accept(entity);
            }, () -> entities.remove(entity.getUniqueId(), entity));
        }
    }

    public static void clear() {
        entities.clear();
        indexedChunks.clear();
        furnitureKey = null;
        registered = false;
    }

    private record ChunkKey(UUID worldId, int x, int z) {}
}
