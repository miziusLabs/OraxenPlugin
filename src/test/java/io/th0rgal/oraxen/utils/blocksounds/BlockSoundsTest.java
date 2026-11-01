package io.th0rgal.oraxen.utils.blocksounds;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockSoundsTest {

    @Test
    void copiedModernSectionKeepsItsParsingModeForLegacyDelegates() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("sounds.hit", "minecraft:block.stone.hit");
        config.set("block_sounds", config.get("sounds"));
        BlockSounds sounds = new BlockSounds(config.getConfigurationSection("block_sounds"));

        assertEquals("minecraft:block.stone.hit", sounds.getHitSound());
        assertEquals(1.0f, sounds.getHitVolume());
        assertEquals(1.0f, sounds.getHitPitch());
    }
    @Test
    void modernSoundsAcceptStringsAndMapsAndDefaultVolumeAndPitchToOne() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                sounds:
                  break: minecraft:block.stone.break
                  place:
                    sound: example:custom.place
                    volume: 0.4
                    pitch: 1.2
                  hit:
                    sound: example:custom.hit
                  step: example:custom.step
                  fall: example:custom.fall
                """);
        BlockSounds sounds = new BlockSounds(config.getConfigurationSection("sounds"));

        assertEquals("minecraft:block.stone.break", sounds.getBreakSound());
        assertEquals(1.0f, sounds.getBreakVolume());
        assertEquals(1.0f, sounds.getBreakPitch());
        assertEquals("example:custom.place", sounds.getPlaceSound());
        assertEquals(0.4f, sounds.getPlaceVolume());
        assertEquals(1.2f, sounds.getPlacePitch());
        assertEquals("example:custom.hit", sounds.getHitSound());
        assertEquals(1.0f, sounds.getHitVolume());
        assertEquals(1.0f, sounds.getHitPitch());
        assertEquals("example:custom.step", sounds.getStepSound());
        assertEquals("example:custom.fall", sounds.getFallSound());
        assertEquals(1.0f, sounds.getStepVolume());
        assertEquals(1.0f, sounds.getFallPitch());
    }

    @Test
    void legacySoundsKeepActionDefaultsAndSharedVolume() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("break-sound", "example:break");
        config.set("place_sound", "example:place");
        config.set("volume", 0.6);
        config.set("hit.sound", "example:hit");
        BlockSounds sounds = new BlockSounds(config);

        assertTrue(sounds.hasBreakSound());
        assertTrue(sounds.hasPlaceSound());
        assertTrue(sounds.hasHitSound());
        assertFalse(sounds.hasFallSound());
        assertEquals(0.6f, sounds.getBreakVolume());
        assertEquals(BlockSounds.VANILLA_BREAK_PITCH, sounds.getBreakPitch());
        assertEquals(BlockSounds.VANILLA_HIT_VOLUME, sounds.getHitVolume());
        assertEquals(BlockSounds.VANILLA_HIT_PITCH, sounds.getHitPitch());
    }
    @Test
    void blockSoundsUseLegacyNoteblockKeyWhenBlockKeyIsMissing() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("noteblock_and_block", false);

        assertFalse(BlockSounds.isBlockSoundEnabled(config));
    }

    @Test
    void stringBlockSoundsUseLegacyStringFurnitureKeyWhenBlockKeyIsMissing() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("stringblock_and_furniture", false);

        assertFalse(BlockSounds.isStringBlockSoundEnabled(config));
    }

    @Test
    void newKeysTakePrecedenceOverLegacyKeys() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("block", true);
        config.set("furniture", true);
        config.set("noteblock_and_block", false);
        config.set("stringblock_and_furniture", false);

        assertTrue(BlockSounds.isBlockSoundEnabled(config));
        assertTrue(BlockSounds.isStringBlockSoundEnabled(config));
        assertTrue(BlockSounds.isFurnitureSoundEnabled(config));
    }
}
