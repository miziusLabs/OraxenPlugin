package io.th0rgal.oraxen.converter;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts an offline setup without loading items or changing the running plugin. */
public final class NexoConverter {
    private static final Set<String> CONFIG_FOLDERS = Set.of("items", "glyphs", "recipes", "custom_armor");
    private static final Set<String> SHARED_CONFIGS = Set.of("settings.yml", "mechanics.yml", "font.yml", "hud.yml");
    private static final Set<String> MECHANICS = Set.of("furniture", "trident", "music_disc", "durability",
            "food", "repair", "hat", "soulbound", "backpack", "armor_effects", "commands", "misc",
            "consumable", "consumable_potion_effects", "aura", "skin", "skinnable", "block");
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, ConfigurationSection> items = new LinkedHashMap<>();
    private final Map<Path, YamlConfiguration> configs = new LinkedHashMap<>();
    private Path staging;

    public record Result(int files, List<String> warnings) { }

    public Result convert(Path input, Path output) throws IOException {
        warnings.clear();
        items.clear();
        configs.clear();
        input = input.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        if (!Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Place the Nexo setup in plugins/Oraxen/converter/Nexo first.");
        if (input.startsWith(output) || output.startsWith(input))
            throw new IOException("Input and output folders must be separate.");
        rejectLinks(input);
        rejectLinks(output);
        if (Files.exists(output)) {
            if (!Files.isDirectory(output)) throw new IOException("Output is not a directory.");
            try (var children = Files.list(output)) {
                if (children.findAny().isPresent())
                    throw new IOException("The converter output folder is not empty. Move or remove it before converting again.");
            }
        }
        Files.createDirectories(output.getParent());
        staging = Files.createTempDirectory(output.getParent(), ".nexo-");
        try {
            List<Path> files;
            try (var walk = Files.walk(input)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                Path relative = input.relativize(file);
                String root = relative.getName(0).toString();
                if (root.equals("pack")) {
                    Path target = relative;
                    if (relative.getNameCount() > 2 && relative.getName(1).toString().equals("external_packs"))
                        target = Path.of("pack", "uploads").resolve(relative.subpath(2, relative.getNameCount()));
                    copy(file, target);
                } else if ((CONFIG_FOLDERS.contains(root) && isYaml(file)) || (relative.getNameCount() == 1
                        && (SHARED_CONFIGS.contains(root) || root.equals("sounds.yml") || root.equals("paintings.yml")))) {
                    YamlConfiguration yaml = load(file);
                    configs.put(relative, yaml);
                    if (root.equals("items")) {
                        for (String id : yaml.getKeys(false)) {
                            ConfigurationSection item = yaml.getConfigurationSection(id);
                            if (item != null && items.putIfAbsent(id, item) != null)
                                throw new IOException("Duplicate item ID '" + id + "' in " + relative);
                        }
                    }
                } else if (root.equals("custom_armor")) {
                    copy(file, relative);
                } else {
                    warn(relative.toString(), "file has no supported import mapping and was skipped");
                }
            }
            for (var entry : configs.entrySet()) {
                Path relative = entry.getKey();
                YamlConfiguration yaml = entry.getValue();
                switch (relative.getName(0).toString()) {
                    case "items" -> {
                        for (String id : yaml.getKeys(false)) {
                            ConfigurationSection item = yaml.getConfigurationSection(id);
                            if (item != null) convertItem(item, relative + " / " + id);
                        }
                    }
                    case "glyphs" -> convertGlyphs(yaml, relative.toString());
                    case "recipes" -> renameReferences(yaml);
                    case "sounds.yml" -> convertSounds(yaml);
                    case "paintings.yml" -> { /* The painting format is shared. */ }
                    default -> {
                        if (relative.toString().equals("mechanics.yml"))
                            io.th0rgal.oraxen.configs.LegacyBlockMechanicMigration.migrate(yaml);
                        if (SHARED_CONFIGS.contains(relative.toString())) yaml = sharedConfig(yaml, relative.toString());
                    }
                }
                writeYaml(relative, yaml);
            }
            Files.writeString(staging.resolve("conversion-report.txt"), report());
            int count;
            try (var walk = Files.walk(staging)) {
                count = (int) walk.filter(Files::isRegularFile).count() - 1;
            }
            if (Files.exists(output)) Files.delete(output);
            Files.move(staging, output);
            return new Result(count, List.copyOf(warnings));
        } finally {
            if (Files.exists(staging)) {
                try (var walk = Files.walk(staging)) {
                    for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private void convertItem(ConfigurationSection item, String context) throws IOException {
        rename(item, "Pack", "pack");
        rename(item, "Components", "components");
        rename(item, "Mechanics", "mechanics");
        ConfigurationSection pack = item.getConfigurationSection("pack");
        if (pack != null) {
            if (pack.isString("texture")) {
                if (!pack.contains("textures")) pack.set("textures", List.of(pack.getString("texture")));
                pack.set("texture", null);
                if (!pack.contains("model")) pack.set("generate_model", true);
            }
            for (String key : List.of("dyeable_model", "dyeable_texture", "composite_models", "composite_textures"))
                unsupported(pack, key, context + ".pack");
            String itemModel = item.getString("components.item_model");
            if (("nexo:" + item.getName()).equals(itemModel)
                    && !Files.exists(safeTarget(Path.of("pack/assets/nexo/items/" + item.getName() + ".json"))))
                item.set("components.item_model", "oraxen:" + item.getName());
        }
        rename(item, "components.equippable.asset_id", "components.equippable.model");
        rename(item, "components.jukebox_playable.song", "components.jukebox_playable.song_key");
        if (item.isString("components.jukebox_playable")) {
            String song = item.getString("components.jukebox_playable");
            item.set("components.jukebox_playable", null);
            item.set("components.jukebox_playable.song_key", song);
        }
        renameReferences(item);
        ConfigurationSection armor = item.getConfigurationSection("CustomArmor");
        if (armor != null) convertArmor(item, armor, context);
        ConfigurationSection mechanics = item.getConfigurationSection("mechanics");
        if (mechanics == null) return;
        for (var legacy : Map.of("noteblock", "FULL", "stringblock", "STRING").entrySet()) {
            if (mechanics.isConfigurationSection(legacy.getKey())) {
                rename(mechanics, legacy.getKey(), "block");
                if (!mechanics.contains("block.type")) mechanics.set("block.type", legacy.getValue());
            }
        }
        for (String key : new ArrayList<>(mechanics.getKeys(false))) {
            if (!MECHANICS.contains(key)) unsupported(mechanics, key, context + ".mechanics");
        }
        ConfigurationSection furniture = mechanics.getConfigurationSection("furniture");
        if (furniture != null) convertFurniture(furniture, context);
        ConfigurationSection trident = mechanics.getConfigurationSection("trident");
        if (trident != null) convertTrident(item, trident, context);
        if (mechanics.isConfigurationSection("music_disc") && !item.contains("components.jukebox_playable")) {
            String song = mechanics.getString("music_disc.song", mechanics.getString("music_disc.sound"));
            if (song != null) {
                item.set("components.jukebox_playable.song_key", song);
                mechanics.set("music_disc", null);
            }
        }
    }

    private void convertGlyphs(YamlConfiguration yaml, String context) {
        YamlConfiguration settings = configs.getOrDefault(Path.of("settings.yml"), new YamlConfiguration());
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection glyph = yaml.getConfigurationSection(id);
            if (glyph == null) continue;
            for (String key : List.of("permission", "placeholders", "tabcomplete")) rename(glyph, key, "chat." + key);
            rename(glyph, "font", "appearance.font");
            rename(glyph, "shadow_color", "appearance.shadow_color");
            if (!glyph.contains("appearance.font")) glyph.set("appearance.font", settings.getString("Glyphs.default_font", "nexo:default"));
            if (!glyph.contains("appearance.shadow_color"))
                glyph.set("appearance.shadow_color", settings.getString("Glyphs.default_shadow_color", "#00000000"));
            if (!glyph.contains("chat.permission")) glyph.set("chat.permission",
                    settings.getString("Glyphs.default_permission", "nexo.glyphs.<glyphid>").replace("<glyphid>", id));
            if (glyph.isInt("char")) glyph.set("char", String.valueOf((char) glyph.getInt("char")));
            for (String key : List.of("animated", "animation", "reference")) unsupported(glyph, key, context + " / " + id);
        }
    }

    private void convertFurniture(ConfigurationSection furniture, String context) {
        furniture.set("type", "DISPLAY_ENTITY");
        rename(furniture, "display_entity_properties", "properties");
        ConfigurationSection hitbox = furniture.getConfigurationSection("hitbox");
        Object barrier = null;
        Object interactions = null;
        if (hitbox != null) {
            barrier = hitbox.get("barrier", hitbox.get("barriers"));
            interactions = hitbox.get("interaction", hitbox.get("interactions"));
            for (String key : hitbox.getKeys(false)) {
                if (!Set.of("barrier", "barriers", "interaction", "interactions").contains(key))
                    warn(context + ".hitbox." + key, "unsupported hitbox setting was skipped");
            }
        } else if (furniture.contains("hitbox")) barrier = furniture.get("hitbox");
        if (barrier != null) {
            List<Map<String, Integer>> barriers = new ArrayList<>();
            for (String value : strings(barrier)) {
                try {
                    barriers.addAll(barriers(value));
                } catch (IllegalArgumentException e) {
                    warn(context + ".hitbox", "barrier expression needs manual conversion");
                }
            }
            furniture.set("barriers", barriers);
        }
        furniture.set("hitboxes", interactions == null ? List.of() : strings(interactions));
        furniture.set("hitbox", null);
        for (String key : List.of("seats", "lights")) {
            if (furniture.isString(key)) furniture.set(key, List.of(furniture.getString(key)));
        }
        ConfigurationSection breakable = furniture.getConfigurationSection("breakable");
        if (breakable != null) {
            furniture.set("hardness", breakable.get("hardness", 1));
            warn(context + ".breakable", "only hardness is mapped; check tool and breaking behavior");
            furniture.set("breakable", true);
        }
        for (String key : List.of("beds", "states", "waterloggable", "item_model", "preview_placement", "clickActions"))
            unsupported(furniture, key, context + ".furniture");
    }

    private static List<Map<String, Integer>> barriers(String expression) {
        if (expression.equals("origin")) return List.of(Map.of("x", 0, "y", 0, "z", 0));
        String[] coordinates = expression.replace(" ", "").split(",");
        if (coordinates.length != 3) throw new IllegalArgumentException();
        int[][] ranges = new int[3][2];
        long count = 1;
        for (int axis = 0; axis < 3; axis++) {
            String[] range = coordinates[axis].split("\\.\\.", -1);
            if (range.length > 2) throw new IllegalArgumentException();
            ranges[axis][0] = Integer.parseInt(range[0]);
            ranges[axis][1] = Integer.parseInt(range[range.length - 1]);
            long length = (long) ranges[axis][1] - ranges[axis][0] + 1;
            if (length < 1 || length > 4096 || count * length > 4096) throw new IllegalArgumentException();
            count *= length;
        }
        List<Map<String, Integer>> result = new ArrayList<>();
        for (long x = ranges[0][0]; x <= ranges[0][1]; x++)
            for (long y = ranges[1][0]; y <= ranges[1][1]; y++)
                for (long z = ranges[2][0]; z <= ranges[2][1]; z++) result.add(Map.of("x", (int) x, "y", (int) y, "z", (int) z));
        return result;
    }

    private void convertTrident(ConfigurationSection item, ConfigurationSection trident, String context) {
        String model = item.getString("pack.model");
        String charging = item.getString("pack.throwing_model", model);
        String thrown = charging;
        String thrownItem = trident.getString("thrown_item");
        if (thrownItem != null) {
            ConfigurationSection definition = items.get(thrownItem);
            thrown = definition == null ? null : definition.getString("Pack.model", definition.getString("pack.model"));
        }
        if (model == null || thrown == null) {
            warn(context + ".trident", "missing model or thrown model; mechanic was skipped, supply Oraxen appearance manually");
            item.set("mechanics.trident", null);
            return;
        }
        trident.set("appearance.model", model);
        trident.set("appearance.charging-model", charging);
        trident.set("appearance.thrown-model", thrown);
        rename(trident, "display_transform", "appearance.transform");
        for (String key : List.of("throw", "hit", "hit_ground", "return")) {
            ConfigurationSection sound = trident.getConfigurationSection("sounds." + key);
            if (sound != null) {
                String id = sound.getString("sound");
                warn(context + ".trident.sounds." + key, "sound ID was mapped; per-sound volume and pitch need manual review");
                trident.set("sounds." + key, id);
            }
        }
        rename(trident, "sounds.hit_ground", "sounds.hit-ground");
        for (String key : List.of("damage", "rotation", "thrown_item_model")) unsupported(trident, key, context + ".trident");
        trident.set("thrown_item", null);
        item.set("pack.throwing_model", null);
    }

    private void convertArmor(ConfigurationSection item, ConfigurationSection armor, String context) throws IOException {
        for (String key : armor.getKeys(false)) {
            if (!Set.of("id", "layer1", "layer2").contains(key))
                warn(context + ".CustomArmor." + key, "only humanoid armor layers are converted; setting was skipped");
        }
        String id = armor.getString("id");
        if (id == null || !id.matches("[a-z0-9/._-]+") || List.of(id.split("/")).contains("..") || List.of(id.split("/")).contains(".")) {
            warn(context + ".CustomArmor", "invalid or missing armor ID; custom armor was skipped");
            item.set("CustomArmor", null);
            return;
        }
        String material = item.getString("material", "").toUpperCase(Locale.ROOT);
        String slot = material.endsWith("HELMET") ? "HEAD" : material.endsWith("CHESTPLATE") ? "CHEST"
                : material.endsWith("LEGGINGS") ? "LEGS" : material.endsWith("BOOTS") ? "FEET" : null;
        if (!item.contains("components.equippable.slot") && slot != null) item.set("components.equippable.slot", slot);
        if (!item.contains("components.equippable.slot")) warn(context + ".CustomArmor", "set an equippable slot for this material");
        if (!item.contains("components.equippable.model")) item.set("components.equippable.model", "nexo:" + id);
        Path target = safeTarget(Path.of("pack/assets/nexo/equipment/" + id + ".json"));
        JsonObject equipment = Files.exists(target) ? JsonParser.parseString(Files.readString(target)).getAsJsonObject() : new JsonObject();
        JsonObject layers = equipment.has("layers") ? equipment.getAsJsonObject("layers") : new JsonObject();
        for (int layer = 1; layer <= 2; layer++) {
            String texture = armor.getString("layer" + layer);
            if (texture == null) continue;
            texture = stripPng(texture);
            String[] key = texture.contains(":") ? texture.split(":", 2) : new String[]{"minecraft", texture};
            Path source = safeTarget(Path.of("pack/assets/" + key[0] + "/textures/" + key[1] + ".png"));
            String type = layer == 1 ? "humanoid" : "humanoid_leggings";
            if (!Files.isRegularFile(source)) {
                warn(context + ".CustomArmor.layer" + layer, "armor texture is missing; supply the equipment texture manually");
                continue;
            }
            Path destination = safeTarget(Path.of("pack/assets/nexo/textures/entity/equipment/" + type + "/" + id + ".png"));
            Files.createDirectories(destination.getParent());
            if (!source.equals(destination)) Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Path metadata = source.resolveSibling(source.getFileName() + ".mcmeta");
            if (Files.isRegularFile(metadata)) Files.copy(metadata, destination.resolveSibling(destination.getFileName() + ".mcmeta"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            layers.add(type, new GsonBuilder().create().toJsonTree(List.of(Map.of("texture", "nexo:" + id))));
        }
        if (!layers.isEmpty()) {
            equipment.add("layers", layers);
            Files.createDirectories(target.getParent());
            Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(equipment));
        }
        item.set("CustomArmor", null);
    }

    private void convertSounds(YamlConfiguration yaml) {
        rename(yaml, "settings.generate_sounds", "settings.automatically_generate");
        if (yaml.isList("sounds")) {
            List<Map<?, ?>> sounds = yaml.getMapList("sounds");
            for (Map<?, ?> entry : sounds) {
                @SuppressWarnings("unchecked") Map<String, Object> sound = (Map<String, Object>) entry;
                if (sound.containsKey("jukebox_playable")) sound.putIfAbsent("jukebox", sound.remove("jukebox_playable"));
            }
            yaml.set("sounds", sounds);
        }
    }

    private YamlConfiguration sharedConfig(YamlConfiguration source, String name) throws IOException {
        YamlConfiguration defaults = new YamlConfiguration();
        try (var stream = NexoConverter.class.getResourceAsStream("/" + name)) {
            if (stream == null) return source;
            defaults.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new IOException("Cannot read Oraxen defaults for " + name, e);
        }
        YamlConfiguration result = new YamlConfiguration();
        if (name.equals("settings.yml") && source.getString("Pack.server.type", "").equalsIgnoreCase("NONE")) {
            result.set("Pack.upload.enabled", false);
            result.set("Pack.dispatch.send", false);
        }
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) continue;
            if (defaults.contains(key) && !key.startsWith("Pack.server.") && !key.startsWith("Pack.upload.")) result.set(key, source.get(key));
            else warn(name + " / " + key, "setting has no shared Oraxen default and was skipped");
        }
        return result;
    }

    private void renameReferences(ConfigurationSection section) {
        for (String key : new ArrayList<>(section.getKeys(false))) {
            Object value = section.get(key);
            if (key.equals("nexo_item")) rename(section, key, "oraxen_item");
            else if (value instanceof ConfigurationSection child) renameReferences(child);
            else if (value instanceof List<?> list) section.set(key, referenceList(list));
        }
    }

    private List<Object> referenceList(List<?> list) {
        List<Object> converted = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, child) -> {
                    if (!key.toString().equals("nexo_item")) result.put(key.toString(), referenceValue(child));
                });
                if (map.containsKey("nexo_item")) result.putIfAbsent("oraxen_item", map.get("nexo_item"));
                converted.add(result);
            } else converted.add(referenceValue(value));
        }
        return converted;
    }

    private Object referenceValue(Object value) {
        if (value instanceof List<?> list) return referenceList(list);
        if (value instanceof Map<?, ?> map) return referenceList(List.of(map)).getFirst();
        return value;
    }

    private static void rename(ConfigurationSection section, String old, String target) {
        if (!section.contains(old)) return;
        Object value = section.get(old);
        if (value instanceof ConfigurationSection child) {
            for (String key : child.getKeys(true)) {
                if (!child.isConfigurationSection(key) && !section.contains(target + "." + key))
                    section.set(target + "." + key, child.get(key));
            }
        } else if (!section.contains(target)) section.set(target, value);
        section.set(old, null);
    }

    private void unsupported(ConfigurationSection section, String key, String context) {
        if (!section.contains(key)) return;
        warn(context + "." + key, "no supported equivalent; skipped");
        section.set(key, null);
    }

    private void warn(String context, String message) { warnings.add(context + " - " + message); }
    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of(value.toString());
    }
    private static String stripPng(String value) { return value.endsWith(".png") ? value.substring(0, value.length() - 4) : value; }
    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
    private static YamlConfiguration load(Path path) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(path.toFile());
        } catch (InvalidConfigurationException e) {
            throw new IOException("Invalid YAML in " + path.getFileName() + ". No output was written.", e);
        }
        return yaml;
    }
    private Path safeTarget(Path relative) throws IOException {
        Path target = staging.resolve(relative).normalize();
        if (!target.startsWith(staging)) throw new IOException("Invalid output path.");
        return target;
    }
    private void copy(Path source, Path relative) throws IOException {
        Path target = safeTarget(relative);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) throw new IOException("Conflicting resource-pack files at " + relative);
        Files.copy(source, target);
    }
    private void writeYaml(Path relative, YamlConfiguration yaml) throws IOException {
        Path target = safeTarget(relative);
        Files.createDirectories(target.getParent());
        yaml.save(target.toFile());
    }
    private static void rejectLinks(Path path) throws IOException {
        for (Path parent = path; parent != null; parent = parent.getParent())
            if (Files.isSymbolicLink(parent)) throw new IOException("Symbolic links are not supported in converter paths.");
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (var walk = Files.walk(path)) {
                if (walk.anyMatch(Files::isSymbolicLink)) throw new IOException("Symbolic links are not supported in converter input or output.");
            }
        }
    }
    private String report() {
        return "Nexo conversion\n\nThe source and live Oraxen setup were not changed.\n"
                + "Back up Oraxen, then merge the output folders and YAML settings into its data folder.\n"
                + "Review conflicts before replacing existing files. Reload Oraxen and rebuild the pack after importing.\n"
                + "Namespaces and asset paths are preserved. Existing placed Nexo furniture and player items are not migrated.\n"
                + "Nexo permissions, commands and PlaceholderAPI placeholders in text may need manual updates.\n"
                + "Upload hosting and unsupported settings need manual configuration.\n\n"
                + (warnings.isEmpty() ? "No conversion warnings.\n" : String.join("\n", warnings) + "\n");
    }
}
