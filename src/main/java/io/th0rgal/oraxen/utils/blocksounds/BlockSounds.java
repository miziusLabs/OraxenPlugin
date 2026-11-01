package io.th0rgal.oraxen.utils.blocksounds;

import io.th0rgal.oraxen.utils.BlockHelpers;
import org.bukkit.configuration.ConfigurationSection;

public class BlockSounds {

    public static String VANILLA_STONE_PLACE = "minecraft:required.stone.place";
    public static String VANILLA_STONE_BREAK = "minecraft:required.stone.break";
    public static String VANILLA_STONE_HIT = "minecraft:required.stone.hit";
    public static String VANILLA_STONE_STEP = "minecraft:required.stone.step";
    public static String VANILLA_STONE_FALL = "minecraft:required.stone.fall";

    public static String VANILLA_WOOD_PLACE = "minecraft:required.wood.place";
    public static String VANILLA_WOOD_BREAK = "minecraft:required.wood.break";
    public static String VANILLA_WOOD_HIT = "minecraft:required.wood.hit";
    public static String VANILLA_WOOD_STEP = "minecraft:required.wood.step";
    public static String VANILLA_WOOD_FALL = "minecraft:required.wood.fall";

    public static float VANILLA_PLACE_VOLUME = 1.0f;
    public static float VANILLA_PLACE_PITCH = 0.8f;
    public static float VANILLA_BREAK_VOLUME = 1.0f;
    public static float VANILLA_BREAK_PITCH = 0.8f;
    public static float VANILLA_HIT_VOLUME = 0.25f;
    public static float VANILLA_HIT_PITCH = 0.5f;
    public static float VANILLA_STEP_VOLUME = 0.15f;
    public static float VANILLA_STEP_PITCH = 1.0f;
    public static float VANILLA_FALL_VOLUME = 0.5f;
    public static float VANILLA_FALL_PITCH = 0.75f;

    public static boolean isBlockSoundEnabled(ConfigurationSection section) {
        return isEnabled(section, "block", "noteblock_and_block", true);
    }

    public static boolean isStringBlockSoundEnabled(ConfigurationSection section) {
        return isEnabled(section, "block", "stringblock_and_furniture", true);
    }

    public static boolean isFurnitureSoundEnabled(ConfigurationSection section) {
        return isEnabled(section, "furniture", "stringblock_and_furniture", true);
    }

    private static boolean isEnabled(ConfigurationSection section, String key, String legacyKey, boolean defaultValue) {
        if (section == null) return defaultValue;
        if (section.contains(key)) return section.getBoolean(key, defaultValue);
        return section.getBoolean(legacyKey, defaultValue);
    }

    private final String placeSound;
    private final String breakSound;
    private final String stepSound;
    private final String hitSound;
    private final String fallSound;
    private final float placeVolume;
    private final float breakVolume;
    private final float stepVolume;
    private final float hitVolume;
    private final float fallVolume;
    private final float placePitch;
    private final float breakPitch;
    private final float stepPitch;
    private final float hitPitch;
    private final float fallPitch;

    private final boolean modern;

    public static BlockSounds fromSounds(ConfigurationSection section) {
        return new BlockSounds(section, true);
    }

    public BlockSounds(ConfigurationSection section) {
        this(section, section.getName().equals("sounds"));
    }

    private BlockSounds(ConfigurationSection section, boolean modern) {
        this.modern = modern;
        placeSound = getSound(section, "place");
        breakSound = getSound(section, "break");
        stepSound = getSound(section, "step");
        hitSound = getSound(section, "hit");
        fallSound = getSound(section, "fall");

        placeVolume = getVolume(section, "place", modern ? 1.0f : VANILLA_PLACE_VOLUME);
        breakVolume = getVolume(section, "break", modern ? 1.0f : VANILLA_BREAK_VOLUME);
        stepVolume = getVolume(section, "step", modern ? 1.0f : VANILLA_STEP_VOLUME);
        hitVolume = getVolume(section, "hit", modern ? 1.0f : VANILLA_HIT_VOLUME);
        fallVolume = getVolume(section, "fall", modern ? 1.0f : VANILLA_FALL_VOLUME);

        placePitch = getPitch(section, "place", modern ? 1.0f : VANILLA_PLACE_PITCH);
        breakPitch = getPitch(section, "break", modern ? 1.0f : VANILLA_BREAK_PITCH);
        stepPitch = getPitch(section, "step", modern ? 1.0f : VANILLA_STEP_PITCH);
        hitPitch = getPitch(section, "hit", modern ? 1.0f : VANILLA_HIT_PITCH);
        fallPitch = getPitch(section, "fall", modern ? 1.0f : VANILLA_FALL_PITCH);
    }

    private String getSound(ConfigurationSection section, String key) {
        ConfigurationSection soundSection = section.getConfigurationSection(key);
        if (modern && section.isString(key)) return section.getString(key);
        if (modern && soundSection != null && soundSection.isString("sound")) return soundSection.getString("sound");
        return section.isString(key + "_sound")
                ? section.getString(key + "_sound")
                : section.isString(key + "-sound")
                ? section.getString(key + "-sound")
                : soundSection != null
                ? soundSection.getString("sound")
                : null;
    }

    private float getVolume(ConfigurationSection section, String type, float defaultValue) {
        ConfigurationSection soundSection = section.getConfigurationSection(type);
        if (soundSection == null) {
            return modern ? defaultValue : (float) section.getDouble("volume", defaultValue);
        } else return (float) soundSection.getDouble("volume", defaultValue);
    }

    private float getPitch(ConfigurationSection section, String type, float defaultValue) {
        ConfigurationSection soundSection = section.getConfigurationSection(type);
        if (soundSection != null) {
            return (float) soundSection.getDouble("pitch", defaultValue);
        } else return defaultValue;
    }

    public boolean hasPlaceSound() {
        return placeSound != null;
    }

    public String getPlaceSound() {
        return modern ? placeSound : BlockHelpers.validateReplacedSounds(placeSound);
    }

    public boolean hasBreakSound() {
        return breakSound != null;
    }

    public String getBreakSound() {
        return modern ? breakSound : BlockHelpers.validateReplacedSounds(breakSound);
    }

    public boolean hasStepSound() {
        return stepSound != null;
    }

    public String getStepSound() {
        return modern ? stepSound : BlockHelpers.validateReplacedSounds(stepSound);
    }

    public boolean hasHitSound() {
        return hitSound != null;
    }

    public String getHitSound() {
        return modern ? hitSound : BlockHelpers.validateReplacedSounds(hitSound);
    }

    public boolean hasFallSound() {
        return fallSound != null;
    }

    public String getFallSound() {
        return modern ? fallSound : BlockHelpers.validateReplacedSounds(fallSound);
    }

    public float getPlaceVolume() {
        return placeVolume;
    }

    public float getBreakVolume() {
        return breakVolume;
    }

    public float getStepVolume() {
        return stepVolume;
    }

    public float getHitVolume() {
        return hitVolume;
    }

    public float getFallVolume() {
        return fallVolume;
    }

    public float getPlacePitch() {
        return placePitch;
    }

    public float getBreakPitch() {
        return breakPitch;
    }

    public float getStepPitch() {
        return stepPitch;
    }

    public float getHitPitch() {
        return hitPitch;
    }

    public float getFallPitch() {
        return fallPitch;
    }
}
