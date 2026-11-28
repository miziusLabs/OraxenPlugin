package io.th0rgal.oraxen.items;

import io.th0rgal.oraxen.compatibilities.provided.ecoitems.WrappedEcoItem;
import io.th0rgal.oraxen.compatibilities.provided.mythiccrucible.WrappedCrucibleItem;
import io.th0rgal.oraxen.nms.NMSHandler;
import io.th0rgal.oraxen.nms.NMSHandlers;
import io.th0rgal.oraxen.utils.AdventureUtils;
import io.th0rgal.oraxen.utils.MinecraftVersion;
import io.th0rgal.oraxen.utils.OraxenYaml;
import io.th0rgal.oraxen.utils.VersionUtil;
import io.th0rgal.oraxen.utils.logs.Logs;
import net.Indyuce.mmoitems.MMOItems;
import net.kyori.adventure.key.Key;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.inventory.meta.components.JukeboxPlayableComponent;
import org.bukkit.inventory.meta.components.ToolComponent;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ItemComponents {

    private static final String MINECRAFT_PREFIX = "minecraft:";

    private final ConfigurationSection section;
    private final Material type;

    public ItemComponents(final ConfigurationSection section, final Material type) {
        this.section = section;
        this.type = type;
    }

    public void apply(final ItemBuilder item, final ConfigurationSection mergedSection) {
        parseDataComponents(item, mergedSection);
    }

    private void parseDataComponents(final ItemBuilder item, final ConfigurationSection section) {
        if (section.contains("name"))
            applyItemName(item, section, "name");
        if (section.contains("custom-name"))
            applyItemName(item, section, "custom-name");

        applyRemainingComponents(item, section.getConfigurationSection("components"));
    }

    /**
     * A non-string value (e.g. an accidental section or list) makes {@code getString} return
     * null; skip it with a warning instead of NPEing and aborting the item's remaining
     * parse stages.
     */
    private void applyItemName(final ItemBuilder item, final ConfigurationSection configSection, final String key) {
        final String name = configSection.getString(key);
        if (name == null) {
            Logs.logWarning("Invalid non-text \"" + key + "\" value for item \"" + this.section.getName() + "\"; skipping it.");
            return;
        }
        if (key.equals("name") && VersionUtil.atOrAbove("1.20.5"))
            item.setItemName(AdventureUtils.parseMiniMessage(name));
        else
            item.setDisplayName(name);
    }

    private void applyRemainingComponents(final ItemBuilder item, final ConfigurationSection components) {
        if (components == null || !VersionUtil.atOrAbove("1.20.5"))
            return;

        MinecraftVersion version = runtimeVersion();
        LinkedHashMap<String, Object> normalized = normalizeComponentKeys(components);
        Set<String> removals = parseRemovedComponents(normalized.get("unset_components"), version);
        prepareRuntimeValues(normalized, removals, version);
        item.setRemovedComponents(genericComponentsAvailable(version) ? removals : Set.of());

        MemoryConfiguration legacyView = configurationView(normalized);
        Set<String> consumed = handleLegacyComponents(item, legacyView, normalized, version);

        if (!genericComponentsAvailable(version))
            return;

        for (final Map.Entry<String, Object> entry : normalized.entrySet()) {
            final String key = entry.getKey();
            if (consumed.contains(key) || key.equals("unset_components"))
                continue;

            Object value = entry.getValue();
            if (key.equals("equippable")) {
                applyModernEquippable(item, value, legacyView.getConfigurationSection("equippable"), version);
                continue;
            }

            if (key.equals("swing_animation") && atLeast(version, "26.3")) {
                Object migrated = normalizeSwingAnimationValue(value);
                for (String animationKey : resolveLegacySwingComponentKeys(normalized))
                    NMSHandlers.getHandler().setComponent(item, animationKey, migrated);
                continue;
            }

            for (final String componentKey : resolveGenericComponentKeys(key, version))
                NMSHandlers.getHandler().setComponent(item, componentKey, value);
        }
    }

    private static boolean genericComponentsAvailable(MinecraftVersion version) {
        return atLeast(version, "1.21.2") && !(NMSHandlers.getHandler() instanceof NMSHandler.EmptyNMSHandler);
    }

    static MinecraftVersion runtimeVersion() {
        return normalizeRuntimeVersion(MinecraftVersion.getCurrentVersion());
    }

    static MinecraftVersion normalizeRuntimeVersion(MinecraftVersion version) {
        if (version.getMajor() == 1 && version.getMinor() >= 26)
            return new MinecraftVersion(version.getMinor(), version.getBuild(), 0);
        return version;
    }

    static boolean atLeast(MinecraftVersion version, String target) {
        return normalizeRuntimeVersion(version).isAtLeast(new MinecraftVersion(target));
    }

    static LinkedHashMap<String, Object> normalizeComponentKeys(ConfigurationSection components) {
        Set<String> unprefixed = new HashSet<>();
        for (String key : components.getKeys(false)) {
            if (!key.contains(":"))
                unprefixed.add(normalizeComponentKey(key));
        }

        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (String key : components.getKeys(false)) {
            String normalizedKey = normalizeComponentKey(key);
            if (key.toLowerCase(Locale.ROOT).startsWith(MINECRAFT_PREFIX) && unprefixed.contains(normalizedKey))
                continue;
            normalized.put(normalizedKey, components.get(key));
        }
        return normalized;
    }

    static String normalizeComponentKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.startsWith(MINECRAFT_PREFIX) ? normalized.substring(MINECRAFT_PREFIX.length()) : normalized;
    }

    private static MemoryConfiguration configurationView(Map<String, Object> values) {
        MemoryConfiguration configuration = new MemoryConfiguration();
        values.forEach((key, value) -> configuration.set(key, configurationValue(value)));
        return configuration;
    }

    private static Object configurationValue(Object value) {
        Map<String, Object> map = copyMap(value);
        if (map == null)
            return value;
        MemoryConfiguration configuration = new MemoryConfiguration();
        map.forEach((key, entry) -> configuration.set(key, configurationValue(entry)));
        return configuration;
    }

    private static Set<String> parseRemovedComponents(Object value, MinecraftVersion version) {
        if (!(value instanceof List<?> list))
            return new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        for (Object entry : list) {
            if (entry == null)
                continue;
            String key = normalizeComponentKey(String.valueOf(entry));
            if (key.equals("swing_animation") && atLeast(version, "26.3")) {
                removed.add("attack_animation");
                removed.add("interact_animation");
            } else if (!key.isEmpty()) {
                removed.add(key);
            }
        }
        return removed;
    }

    static void prepareRuntimeValues(Map<String, Object> values, Set<String> removals, MinecraftVersion version) {
        values.replaceAll((key, value) -> runtimeAlias(key, value, removals, version));
        values.values().removeIf(Objects::isNull);

        Map<String, Object> toolMap = copyMap(values.get("tool"));
        if (toolMap != null && hasNativeToolShape(toolMap, version))
            values.put("tool", normalizeToolComponentValue(toolMap, version));

        if (atLeast(version, "1.21.2") && values.get("food") instanceof Object food) {
            Map<String, Object> foodMap = copyMap(food);
            if (foodMap != null && foodMap.containsKey("eat_seconds")) {
                Map<String, Object> consumable = copyMap(values.get("consumable"));
                if (consumable == null)
                    consumable = new LinkedHashMap<>();
                consumable.putIfAbsent("consume_seconds", foodMap.get("eat_seconds"));
                values.put("consumable", consumable);
            }
        }

        if (atLeast(version, "1.21.5")) {
            Map<String, Object> tooltip = copyMap(values.get("tooltip_display"));
            List<String> hidden = new ArrayList<>();
            for (String key : List.of("jukebox_playable", "attribute_modifiers", "enchantments",
                    "stored_enchantments", "dyed_color")) {
                Map<String, Object> component = copyMap(values.get(key));
                if (component == null)
                    continue;
                if (Boolean.FALSE.equals(component.remove("show_in_tooltip")))
                    hidden.add("minecraft:" + key);
                Object flattened = switch (key) {
                    case "jukebox_playable" -> component.containsKey("song")
                            ? component.get("song") : component.get("song_key");
                    case "attribute_modifiers" -> component.get("modifiers");
                    case "enchantments", "stored_enchantments" -> component.get("levels");
                    case "dyed_color" -> component.get("rgb");
                    default -> null;
                };
                if (flattened != null && component.size() == 1)
                    values.put(key, flattened);
                else
                    values.put(key, component);
            }
            if (!hidden.isEmpty() && tooltip == null)
                tooltip = new LinkedHashMap<>();
            if (tooltip != null) {
                if (!hidden.isEmpty())
                    tooltip.putIfAbsent("hidden_components", hidden);
                if (values.get("hide_tooltip") instanceof Boolean hideTooltip)
                    tooltip.putIfAbsent("hide_tooltip", hideTooltip);
                values.put("tooltip_display", tooltip);
            }
        }
    }

    private static Object runtimeAlias(String key, Object value, Set<String> removals, MinecraftVersion version) {
        if (key.equals("custom_model_data") && value instanceof Number number && atLeast(version, "1.21.4"))
            return Map.of("floats", List.of(number));

        if ((key.equals("glider") && atLeast(version, "1.21.2"))
                || (key.equals("unbreakable") && atLeast(version, "1.21.5"))) {
            if (value instanceof Boolean bool) {
                if (!bool)
                    removals.add(key);
                return bool ? Collections.emptyMap() : null;
            }
        }

        if ((key.equals("attack_animation") || key.equals("interact_animation")) && atLeast(version, "26.3"))
            return normalizeSwingAnimationValue(value);
        if (key.equals("rarity") && value instanceof String rarity)
            return rarity.toLowerCase(Locale.ROOT);
        if (key.equals("damage_resistant") && value instanceof String tag && atLeast(version, "1.21.2")) {
            String types = tag.startsWith("#") ? tag : "#" + tag;
            return Map.of("types", types);
        }
        if (key.equals("enchantable") && value instanceof Number number)
            return Map.of("value", number);
        if (key.equals("repairable") && (value instanceof String || value instanceof List<?>))
            return Map.of("items", value);
        if (key.equals("tooltip_display") && value instanceof List<?> list)
            return Map.of("hidden_components", new ArrayList<>(list));
        return value;
    }

    static List<String> resolveGenericComponentKeys(final String key, MinecraftVersion version) {
        final String normalizedKey = normalizeComponentKey(key);
        version = normalizeRuntimeVersion(version);

        if (normalizedKey.equals("map_color") && atLeast(version, "26.3"))
            return List.of();
        if (normalizedKey.equals("swing_animation") && atLeast(version, "26.3"))
            return List.of("attack_animation", "interact_animation");

        return List.of(normalizedKey);
    }

    static List<String> resolveLegacySwingComponentKeys(Map<String, Object> configuredComponents) {
        List<String> keys = new java.util.ArrayList<>(2);
        if (!configuredComponents.containsKey("attack_animation"))
            keys.add("attack_animation");
        if (!configuredComponents.containsKey("interact_animation"))
            keys.add("interact_animation");
        return keys;
    }

    static Object normalizeSwingAnimationValue(Object value) {
        if (value instanceof String type)
            return type.toLowerCase(Locale.ROOT);
        Map<String, Object> map = copyMap(value);
        if (map == null)
            return value;
        Object type = map.get("type");
        if (type instanceof String string)
            map.put("type", string.toLowerCase(Locale.ROOT));
        return map;
    }

    private Set<String> handleLegacyComponents(final ItemBuilder item, final ConfigurationSection components,
            Map<String, Object> normalized, MinecraftVersion version) {
        Set<String> consumed = new LinkedHashSet<>();

        if (OraxenYaml.contains(components, "durability")) {
            item.setDamagedOnBlockBreak(OraxenYaml.getBoolean(components, "durability.damage_block_break"));
            item.setDamagedOnEntityHit(OraxenYaml.getBoolean(components, "durability.damage_entity_hit"));
            item.setDurability(Math.max(OraxenYaml.getInt(components, "durability.value"),
                    OraxenYaml.getInt(components, "durability", 1)));
            consumed.add("durability");
        }
        if (OraxenYaml.contains(components, "fire_resistant")) {
            item.setFireResistant(OraxenYaml.getBoolean(components, "fire_resistant"));
            consumed.add("fire_resistant");
        }
        if (OraxenYaml.contains(components, "hide_tooltip")) {
            item.setHideToolTip(OraxenYaml.getBoolean(components, "hide_tooltip"));
            consumed.add("hide_tooltip");
        }
        if (OraxenYaml.contains(components, "max_stack_size")) {
            item.setMaxStackSize(OraxenYaml.getInt(components, "max_stack_size"));
            consumed.add("max_stack_size");
        }
        if (OraxenYaml.contains(components, "enchantment_glint_override")) {
            item.setEnchantmentGlintOverride(OraxenYaml.getBoolean(components, "enchantment_glint_override"));
            consumed.add("enchantment_glint_override");
        }

        Optional.ofNullable(OraxenYaml.getConfigurationSection(components, "food"))
                .ifPresent(food -> {
                    parseFoodComponent(item, food);
                    consumed.add("food");
                });

        Optional.ofNullable(OraxenYaml.getConfigurationSection(components, "tool"))
                .ifPresent(toolSection -> {
                    if (!useNativeTool(toolSection, version) || !genericComponentsAvailable(version)) {
                        parseToolComponent(item, toolSection);
                        consumed.add("tool");
                    }
                });

        if (!atLeast(version, "1.21")) {
            applySafeOlderComponentAliases(item, normalized, version);
            return consumed;
        }

        Optional.ofNullable(OraxenYaml.getString(components, "painting_variant"))
                .ifPresent(value -> {
                    item.setPaintingVariant(value);
                    consumed.add("painting_variant");
                });

        if (handleJukeboxComponent(item, components, version))
            consumed.add("jukebox_playable");

        if (!atLeast(version, "1.21.2")) {
            applySafeOlderComponentAliases(item, normalized, version);
            return consumed;
        }

        Optional.ofNullable(OraxenYaml.getConfigurationSection(components, "death_protection"))
                .ifPresent(deathProtection -> {
                    EffectsShape shape = effectsShape(deathProtection, "death_effects");
                    if (shape == EffectsShape.MIXED) {
                        Logs.logWarning("Mixed legacy and vanilla death_protection effects in item " + section.getName());
                        consumed.add("death_protection");
                    } else if (shape != EffectsShape.NATIVE || !genericComponentsAvailable(version)) {
                        NMSHandlers.getHandler().deathProtectionComponent(item, deathProtection);
                        consumed.add("death_protection");
                    }
                });

        Optional.ofNullable(OraxenYaml.getConfigurationSection(components, "equippable"))
                .ifPresent(equippable -> {
                    if (!genericComponentsAvailable(version)) {
                        parseEquippableComponent(item, equippable);
                        consumed.add("equippable");
                    }
                });

        Optional.ofNullable(OraxenYaml.getConfigurationSection(components, "use_cooldown")).ifPresent(cooldownSection -> {
            try {
                final UseCooldownComponent useCooldownComponent = new ItemStack(Material.PAPER).getItemMeta()
                        .getUseCooldown();
                final String group = Optional.ofNullable(cooldownSection.getString("cooldown_group"))
                        .orElseGet(() -> Optional.ofNullable(cooldownSection.getString("group"))
                                .orElse("oraxen:" + section.getName()));
                if (!group.isEmpty())
                    useCooldownComponent.setCooldownGroup(NamespacedKey.fromString(group));
                useCooldownComponent
                        .setCooldownSeconds((float) Math.max(cooldownSection.getDouble("seconds", 1.0), 0f));
                item.setUseCooldownComponent(useCooldownComponent);
                consumed.add("use_cooldown");
            } catch (final NoSuchMethodError | Exception e) {
                Logs.logWarning(
                        "Error setting UseCooldownComponent: This component is not available in your server version");
                Logs.debug(e);
            }
        });

        Optional.ofNullable(OraxenYaml.getConfigurationSection(components, "use_remainder"))
                .ifPresent(useRemainder -> {
                    if (!useRemainder.contains("id")) {
                        parseUseRemainderComponent(item, useRemainder);
                        consumed.add("use_remainder");
                    }
                });

        Optional.ofNullable(OraxenYaml.getString(components, "tooltip_style")).map(NamespacedKey::fromString)
                .ifPresent(value -> {
                    item.setTooltipStyle(value);
                    consumed.add("tooltip_style");
                });
        Optional.ofNullable(OraxenYaml.getString(components, "item_model")).map(NamespacedKey::fromString)
                .ifPresent(value -> {
                    item.setItemModel(value);
                    consumed.add("item_model");
                });

        Optional.ofNullable(OraxenYaml.getConfigurationSection(components, "consumable"))
                .ifPresent(consumableSection -> {
                    EffectsShape shape = effectsShape(consumableSection, "on_consume_effects");
                    if (shape == EffectsShape.MIXED) {
                        Logs.logWarning("Mixed legacy and vanilla consumable effects in item " + section.getName());
                        consumed.add("consumable");
                    } else if (shape != EffectsShape.NATIVE || !genericComponentsAvailable(version)) {
                        NMSHandlers.getHandler().consumableComponent(item, consumableSection);
                        consumed.add("consumable");
                    }
                });

        if (!genericComponentsAvailable(version))
            applySafeOlderComponentAliases(item, normalized, version);

        return consumed;
    }

    private boolean handleJukeboxComponent(ItemBuilder item, ConfigurationSection components, MinecraftVersion version) {
        final Object configured = components.get("jukebox_playable");
        final ConfigurationSection jukeboxSection = OraxenYaml.getConfigurationSection(components, "jukebox_playable");
        if (configured instanceof String)
            return !atLeast(version, "1.21.5") && applyLegacyJukeboxSong(item, String.valueOf(configured));
        if (jukeboxSection == null)
            return configured != null && !genericComponentsAvailable(version);
        if (genericComponentsAvailable(version) && atLeast(version, "1.21.5"))
            return false;
        try {
            final JukeboxPlayableComponent jukeboxPlayable = new ItemStack(Material.MUSIC_DISC_CREATOR)
                    .getItemMeta()
                    .getJukeboxPlayable();

            if (!atLeast(version, "1.21.5")) {
                try {
                    jukeboxPlayable.setShowInTooltip(jukeboxSection.getBoolean("show_in_tooltip", true));
                } catch (final NoSuchMethodError e) {
                    Logs.logWarning(
                            "Error setting jukebox show_in_tooltip: This method is not available in your server version");
                    Logs.debug(e);
                }
            }

            try {
                String song = Optional.ofNullable(jukeboxSection.getString("song"))
                        .orElseGet(() -> jukeboxSection.getString("song_key"));
                if (song == null)
                    return false;
                jukeboxPlayable.setSongKey(NamespacedKey.fromString(song));
            } catch (final NoSuchMethodError e) {
                if (!atLeast(version, "1.21.5")) {
                    Logs.logWarning(
                            "Error setting jukebox song_key: This method is not available in your server version");
                    Logs.debug(e);
                }
            }

            item.setJukeboxPlayable(jukeboxPlayable);
        } catch (final Exception e) {
            Logs.logWarning("Failed to create JukeboxPlayableComponent for item: " + section.getName());
            Logs.debug(e);
        }
        return true;
    }

    private boolean applyLegacyJukeboxSong(ItemBuilder item, String song) {
        if (!atLeast(runtimeVersion(), "1.21") || atLeast(runtimeVersion(), "1.21.5"))
            return false;
        try {
            final JukeboxPlayableComponent jukeboxPlayable = new ItemStack(Material.MUSIC_DISC_CREATOR)
                    .getItemMeta()
                    .getJukeboxPlayable();
            jukeboxPlayable.setSongKey(NamespacedKey.fromString(song));
            item.setJukeboxPlayable(jukeboxPlayable);
            return true;
        } catch (RuntimeException | LinkageError exception) {
            Logs.logWarning("Failed to apply jukebox song for item: " + section.getName());
            Logs.debug(exception);
            return true;
        }
    }

    private void applySafeOlderComponentAliases(ItemBuilder item, Map<String, Object> values, MinecraftVersion version) {
        if (values.get("max_damage") instanceof Number maxDamage)
            item.setDurability(maxDamage.intValue());
        if (values.get("rarity") instanceof String rarity) {
            ItemRarity parsed = EnumUtils.getEnum(ItemRarity.class, rarity.toUpperCase(Locale.ROOT));
            if (parsed != null)
                item.setRarity(parsed);
        }
        if (values.get("custom_model_data") instanceof Number customModelData)
            item.setCustomModelData(customModelData.intValue());
        if (values.get("damage") instanceof Number damage)
            item.setDamage(damage.intValue());
        if (values.get("repair_cost") instanceof Number repairCost)
            item.setRepairCost(repairCost.intValue());
    }

    private static boolean useNativeTool(ConfigurationSection toolSection, MinecraftVersion version) {
        Map<String, Object> tool = copyMap(toolSection);
        return tool != null && hasNativeToolShape(tool, version);
    }

    private static boolean hasNativeToolShape(Map<String, Object> tool, MinecraftVersion version) {
        if (tool.containsKey("can_destroy_blocks_in_creative") && atLeast(version, "1.21.5"))
            return true;
        if (tool.get("rules") instanceof List<?> rules)
            for (Object ruleValue : rules) {
                Map<String, Object> rule = copyMap(ruleValue);
                if (rule != null && rule.containsKey("blocks"))
                    return true;
            }
        return false;
    }

    static boolean isNativeEffectsShape(Object value, String effectsKey) {
        return effectsShape(value, effectsKey) == EffectsShape.NATIVE;
    }

    enum EffectsShape { LEGACY, NATIVE, MIXED }

    static EffectsShape effectsShape(Object value, String effectsKey) {
        Map<String, Object> map = copyMap(value);
        if (map == null)
            return EffectsShape.LEGACY;
        boolean nativeShape = map.get("sound") instanceof Map<?, ?>
                || map.get("sound") instanceof ConfigurationSection;
        boolean legacyShape = false;
        if (!(map.get(effectsKey) instanceof List<?> effects))
            return nativeShape ? EffectsShape.NATIVE : EffectsShape.LEGACY;
        for (Object effectValue : effects) {
            Map<String, Object> effect = copyMap(effectValue);
            if (effect == null)
                continue;
            String type = String.valueOf(effect.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
            if (type.startsWith(MINECRAFT_PREFIX))
                type = type.substring(MINECRAFT_PREFIX.length());
            if (type.equals("apply_effects")) {
                if (effect.get("effects") instanceof Map<?, ?> || effect.get("effects") instanceof ConfigurationSection)
                    legacyShape = true;
                else
                    nativeShape = true;
            } else if (type.equals("remove_effects")) {
                if (hasNativeRemoveEffectsShape(effect.get("effects")))
                    nativeShape = true;
                else
                    legacyShape = true;
            } else if (type.contains(":") || effect.get("sound") instanceof Map<?, ?>
                    || effect.get("sound") instanceof ConfigurationSection) {
                nativeShape = true;
            }
        }
        if (nativeShape && legacyShape)
            return EffectsShape.MIXED;
        return nativeShape ? EffectsShape.NATIVE : EffectsShape.LEGACY;
    }

    private static boolean hasNativeRemoveEffectsShape(Object effects) {
        if (effects instanceof String string)
            return true;
        if (effects instanceof List<?> list)
            return list.stream().anyMatch(entry -> String.valueOf(entry).startsWith("#"));
        return effects != null;
    }

    @SuppressWarnings("UnstableApiUsage")
    private void parseFoodComponent(final ItemBuilder item, final ConfigurationSection foodSection) {
        final FoodComponent foodComponent = new ItemStack(item.getType()).getItemMeta().getFood();
        if (!(foodSection.get("nutrition") instanceof Number))
            Logs.logWarning("Food component of item \"" + section.getName() + "\" has no valid nutrition value, using 0.");
        foodComponent.setNutrition(Math.max(foodSection.getInt("nutrition"), 0));
        foodComponent.setSaturation(Math.max((float) foodSection.getDouble("saturation", 0.0), 0f));
        foodComponent.setCanAlwaysEat(foodSection.getBoolean("can_always_eat", false));
        if (foodSection.contains("eat_seconds") && !VersionUtil.atOrAbove("1.21.2")) {
            try {
                Method setter = foodComponent.getClass().getMethod("setEatSeconds", float.class);
                setter.invoke(foodComponent, (float) foodSection.getDouble("eat_seconds"));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Paper removed the eating time API after the consumable component replaced it.
            }
        }
        item.setFoodComponent(foodComponent);
    }

    private void parseUseRemainderComponent(final ItemBuilder item,
            @NotNull final ConfigurationSection useRemainderSection) {
        final ItemStack result;
        final int amount = useRemainderSection.getInt("amount", 1);

        if (useRemainderSection.contains("oraxen_item")) {
            item.deferUseRemainder(useRemainderSection.getString("oraxen_item"), amount);
            return;
        } else if (useRemainderSection.contains("crucible_item"))
            result = new WrappedCrucibleItem(useRemainderSection.getString("crucible_item")).build();
        else if (useRemainderSection.contains("mmoitems_id") && useRemainderSection.isString("mmoitems_type"))
            result = MMOItems.plugin.getItem(useRemainderSection.getString("mmoitems_type"),
                    useRemainderSection.getString("mmoitems_id"));
        else if (useRemainderSection.contains("ecoitem_id"))
            result = new WrappedEcoItem(useRemainderSection.getString("ecoitem_id")).build();
        else if (useRemainderSection.contains("minecraft_type")) {
            final Material material = OraxenYaml.getMaterial(useRemainderSection.getString("minecraft_type", "AIR"));
            if (material == null || material.isAir())
                return;
            result = new ItemStack(material);
        } else
            result = useRemainderSection.getItemStack("minecraft_item");

        if (result != null)
            result.setAmount(amount);
        item.setUseRemainder(result);
    }

    @SuppressWarnings({ "UnstableApiUsage", "unchecked" })
    private void parseToolComponent(final ItemBuilder item, @NotNull final ConfigurationSection toolSection) {
        final ToolComponent toolComponent = new ItemStack(type).getItemMeta().getTool();
        toolComponent.setDamagePerBlock(Math.max(toolSection.getInt("damage_per_block", 1), 0));
        toolComponent.setDefaultMiningSpeed(Math.max((float) toolSection.getDouble("default_mining_speed", 1.0), 0f));

        for (final Map<?, ?> ruleEntry : toolSection.getMapList("rules")) {
            final float speed = NumberUtils.toFloat(String.valueOf(ruleEntry.get("speed")), 1f);
            final boolean correctForDrops = Boolean.parseBoolean(String.valueOf(ruleEntry.get("correct_for_drops")));
            final Set<Material> materials = new HashSet<>();
            final Set<Tag<Material>> tags = new HashSet<>();

            if (ruleEntry.containsKey("material")) {
                addLegacyMaterial(ruleEntry.get("material"), materials);
            }

            if (ruleEntry.containsKey("materials")) {
                try {
                    final List<String> materialIds = (List<String>) ruleEntry.get("materials");
                    for (final String materialId : materialIds)
                        addLegacyMaterial(materialId, materials);
                } catch (final Exception e) {
                    Logs.logWarning("Error parsing rule-entry in " + section.getName());
                    Logs.logWarning("Malformed \"materials\"-section");
                    Logs.debug(e);
                }
            }

            if (ruleEntry.containsKey("tag")) {
                addLegacyTag(String.valueOf(ruleEntry.get("tag")), tags);
            }

            if (ruleEntry.containsKey("tags")) {
                try {
                    for (final String tagString : (List<String>) ruleEntry.get("tags"))
                        addLegacyTag(tagString, tags);
                } catch (final Exception e) {
                    Logs.logWarning("Error parsing rule-entry in " + section.getName());
                    Logs.logWarning("Malformed \"tags\"-section");
                    Logs.debug(e);
                }
            }

            Object blocks = ruleEntry.get("blocks");
            if (blocks instanceof String block) {
                if (block.startsWith("#"))
                    addLegacyTag(block.substring(1), tags);
                else
                    addLegacyMaterial(block, materials);
            } else if (blocks instanceof List<?> blockList)
                for (Object block : blockList)
                    if (String.valueOf(block).startsWith("#"))
                        addLegacyTag(String.valueOf(block).substring(1), tags);
                    else
                        addLegacyMaterial(String.valueOf(block), materials);

            if (!materials.isEmpty())
                toolComponent.addRule(materials, speed, correctForDrops);
            for (final Tag<Material> tag : tags)
                if (tag != null)
                    toolComponent.addRule(tag, speed, correctForDrops);
        }

        item.setToolComponent(toolComponent);
    }

    private void addLegacyMaterial(Object materialId, Set<Material> materials) {
        try {
            final Material material = OraxenYaml.getMaterial(String.valueOf(materialId));
            if (material == null)
                throw new IllegalArgumentException("Unknown material");
            if (material.isBlock())
                materials.add(material);
        } catch (final Exception e) {
            Logs.logWarning("Error parsing rule-entry in " + section.getName());
            Logs.logWarning("Malformed \"material\"-section");
            Logs.debug(e);
        }
    }

    private void addLegacyTag(String tagValue, Set<Tag<Material>> tags) {
        try {
            final NamespacedKey tagKey = NamespacedKey.fromString(tagValue);
            if (tagKey != null) {
                Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, tagKey, Material.class);
                if (tag != null)
                    tags.add(tag);
            }
        } catch (final Exception e) {
            Logs.logWarning("Error parsing rule-entry in " + section.getName());
            Logs.logWarning("Malformed \"tag\"-section");
            Logs.debug(e);
        }
    }

    static Object normalizeToolComponentValue(Object value, MinecraftVersion version) {
        Map<String, Object> tool = copyMap(value);
        if (tool == null)
            return value;

        if (tool.containsKey("can_destroy_blocks_in_creative") && !atLeast(normalizeRuntimeVersion(version), "1.21.5"))
            tool.remove("can_destroy_blocks_in_creative");

        if (!(tool.get("rules") instanceof List<?> configuredRules)) {
            tool.putIfAbsent("rules", List.of());
            return tool;
        }

        List<Map<String, Object>> rules = new ArrayList<>();
        boolean changed = false;
        for (Object ruleValue : configuredRules) {
            Map<String, Object> rule = copyMap(ruleValue);
            if (rule == null)
                continue;
            if (rule.containsKey("blocks")) {
                rules.add(rule);
                continue;
            }

            List<String> materials = new ArrayList<>();
            List<String> tags = new ArrayList<>();
            collectMaterialIds(rule.get("material"), materials);
            collectMaterialIds(rule.get("materials"), materials);
            collectTagIds(rule.get("tag"), tags);
            collectTagIds(rule.get("tags"), tags);
            rule.remove("material");
            rule.remove("materials");
            rule.remove("tag");
            rule.remove("tags");

            if (materials.isEmpty() && tags.isEmpty()) {
                rules.add(rule);
                continue;
            }

            changed = true;
            if (!materials.isEmpty()) {
                Map<String, Object> materialRule = new LinkedHashMap<>(rule);
                materialRule.put("blocks", materials.size() == 1 ? materials.get(0) : materials);
                rules.add(materialRule);
            }
            for (String tag : tags) {
                Map<String, Object> tagRule = new LinkedHashMap<>(rule);
                tagRule.put("blocks", tag);
                rules.add(tagRule);
            }
        }
        if (changed || rules.size() != configuredRules.size())
            tool.put("rules", rules);
        return tool;
    }

    private static void collectMaterialIds(Object value, List<String> materials) {
        if (value instanceof List<?> list) {
            for (Object entry : list)
                collectMaterialIds(entry, materials);
        } else if (value != null) {
            String material = String.valueOf(value).toLowerCase(Locale.ROOT);
            materials.add(material.contains(":") ? material : MINECRAFT_PREFIX + material);
        }
    }

    private static void collectTagIds(Object value, List<String> tags) {
        if (value instanceof List<?> list) {
            for (Object entry : list)
                collectTagIds(entry, tags);
        } else if (value != null) {
            String tag = String.valueOf(value).toLowerCase(Locale.ROOT);
            if (tag.startsWith("#"))
                tags.add(tag);
            else
                tags.add("#" + (tag.contains(":") ? tag : MINECRAFT_PREFIX + tag));
        }
    }

    static Map<String, Object> normalizeEquippableMap(Object value, MinecraftVersion version) {
        Map<String, Object> equippable = copyMap(value);
        if (equippable == null)
            return null;
        version = normalizeRuntimeVersion(version);

        Object slot = equippable.get("slot");
        if (slot instanceof String string) {
            String normalizedSlot = string.toLowerCase(Locale.ROOT);
            if (normalizedSlot.equals("main_hand"))
                normalizedSlot = "mainhand";
            else if (normalizedSlot.equals("off_hand"))
                normalizedSlot = "offhand";
            equippable.put("slot", normalizedSlot);
        }

        Object model = equippable.containsKey("asset_id") ? equippable.get("asset_id") : equippable.get("model");
        equippable.remove("asset_id");
        equippable.remove("model");
        if (model != null)
            equippable.put(atLeast(version, "1.21.4") ? "asset_id" : "model", model);

        if (!equippable.containsKey("allowed_entities") && equippable.containsKey("allowed_entity_types")) {
            equippable.put("allowed_entities", normalizeEntityTypeIds(equippable.get("allowed_entity_types")));
            equippable.remove("allowed_entity_types");
        }
        return equippable;
    }

    private static Object normalizeEntityTypeIds(Object value) {
        if (value instanceof List<?> list) {
            List<String> normalized = new ArrayList<>();
            for (Object entry : list)
                normalized.add(normalizeEntityTypeId(entry));
            return normalized;
        }
        return normalizeEntityTypeId(value);
    }

    private static String normalizeEntityTypeId(Object value) {
        String id = String.valueOf(value).toLowerCase(Locale.ROOT);
        if (id.startsWith("#"))
            return id.contains(":") ? id : "#minecraft:" + id.substring(1);
        return id.contains(":") ? id : MINECRAFT_PREFIX + id;
    }

    private void applyModernEquippable(ItemBuilder item, Object value, ConfigurationSection legacySection,
            MinecraftVersion version) {
        Map<String, Object> normalized = normalizeEquippableMap(value, version);
        NMSHandler handler = NMSHandlers.getHandler();
        boolean decoded = false;
        if (normalized != null) {
            ItemBuilder scratch = new ItemBuilder(item.getType());
            if (handler.setComponent(scratch, "equippable", normalized)) {
                Object component = scratch.getComponent("equippable");
                ItemStack decodedStack = handler.applyGenericComponents(new ItemStack(item.getType()),
                        Map.of("equippable", component));
                ItemMeta meta = decodedStack.getItemMeta();
                if (meta != null && meta.hasEquippable()) {
                    item.setEquippableComponent(meta.getEquippable());
                    decoded = true;
                }
            }
        }

        if (!decoded && legacySection != null && !hasModernOnlyEquippableFields(copyMap(value)))
            parseEquippableComponent(item, legacySection);
    }

    private static boolean hasModernOnlyEquippableFields(Map<String, Object> equippable) {
        if (equippable == null)
            return false;
        return equippable.containsKey("asset_id") || equippable.containsKey("allowed_entities")
                || equippable.containsKey("equip_on_interact") || equippable.containsKey("can_be_sheared")
                || equippable.containsKey("shearing_sound");
    }

    private void parseEquippableComponent(final ItemBuilder item, final ConfigurationSection equippableSection) {
        final EquippableComponent equippableComponent = new ItemStack(type).getItemMeta().getEquippable();

        final String slot = equippableSection.getString("slot");
        try {
            equippableComponent.setSlot(EquipmentSlot.valueOf(slot == null ? "" : slot.toUpperCase(Locale.ROOT)));
        } catch (final Exception e) {
            Logs.logWarning("Error parsing equippable-component in %s...".formatted(section.getName()));
            Logs.logWarning("Invalid \"slot\"-value %s".formatted(slot));
            Logs.logWarning("Valid values are: %s".formatted(StringUtils.join(EquipmentSlot.values())));
            return;
        }

        final List<EntityType> entityTypes = equippableSection.getStringList("allowed_entity_types").stream()
                .map(e -> EnumUtils.getEnum(EntityType.class, e.toUpperCase(Locale.ROOT))).toList();
        if (equippableSection.contains("allowed_entity_types"))
            equippableComponent.setAllowedEntities(entityTypes.isEmpty() ? null : entityTypes);
        if (equippableSection.contains("damage_on_hurt"))
            equippableComponent.setDamageOnHurt(equippableSection.getBoolean("damage_on_hurt", true));
        if (equippableSection.contains("dispensable"))
            equippableComponent.setDispensable(equippableSection.getBoolean("dispensable", true));
        if (equippableSection.contains("swappable"))
            equippableComponent.setSwappable(equippableSection.getBoolean("swappable", true));

        Optional.ofNullable(equippableSection.getString("model", null)).map(NamespacedKey::fromString)
                .ifPresent(equippableComponent::setModel);
        Optional.ofNullable(equippableSection.getString("asset_id", null)).map(NamespacedKey::fromString)
                .ifPresent(equippableComponent::setModel);
        Optional.ofNullable(equippableSection.getString("camera_overlay")).map(NamespacedKey::fromString)
                .ifPresent(equippableComponent::setCameraOverlay);

        if (equippableSection.contains("equip_sound")) {
            try {
                Optional.ofNullable(equippableSection.getString("equip_sound"))
                        .map(Key::key)
                        .map(key -> org.bukkit.Registry.SOUNDS.get(key))
                        .ifPresent(equippableComponent::setEquipSound);
            } catch (final NoSuchMethodError e) {
                Logs.logWarning("Error setting equip_sound: Your server version doesn't support this feature.");
            }
        }

        item.setEquippableComponent(equippableComponent);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> copyMap(Object value) {
        if (value instanceof ConfigurationSection section)
            value = section.getValues(false);
        if (!(value instanceof Map<?, ?> input))
            return null;
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key))
                continue;
            copy.put(key, copyValue(entry.getValue()));
        }
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof ConfigurationSection section)
            return copyMap(section);
        if (value instanceof Map<?, ?>)
            return copyMap(value);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object entry : list)
                copy.add(copyValue(entry));
            return copy;
        }
        return value;
    }
}
