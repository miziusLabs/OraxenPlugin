package io.th0rgal.oraxen.mechanics.provided.gameplay.noteblock;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.configs.ConfigsManager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteBlockModelReloadTest {

    @Test
    void itemReloadPublishesOnlyVariantsFromTheCurrentItemSet() {
        String oldVariant = "instrument=basedrum,note=1,powered=false";
        String oldModel = "oraxen:block/removed_native_block";

        try {
            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.registerBlockstateVariant(oldVariant,
                    NoteBlockMechanicFactory.getModelJson(oldModel));
            NoteBlockMechanicFactory.commitNativeMappings();
            assertTrue(NoteBlockMechanicFactory.getBlockstateContent().contains(oldModel));

            NoteBlockMechanicFactory.beginNativeMappingsReload();
            assertTrue(NoteBlockMechanicFactory.getBlockstateContent().contains(oldModel),
                    "players keep the published blockstate mapping until reload commits");
            NoteBlockMechanicFactory.commitNativeMappings();

            String newBlockstate = NoteBlockMechanicFactory.getBlockstateContent();
            assertFalse(newBlockstate.contains(oldModel));
            assertTrue(newBlockstate.contains("block/note_block"));
        } finally {
            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.commitNativeMappings();
            NoteBlockMechanicFactory.discardNativeMappings();
        }
    }

    @Test
    void failedItemReloadKeepsPublishedBlockstateMappings() {
        String activeModel = "oraxen:block/active_native_block";
        String stagedModel = "oraxen:block/uncommitted_native_block";

        try {
            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.registerBlockstateVariant("instrument=basedrum,note=1,powered=false",
                    NoteBlockMechanicFactory.getModelJson(activeModel));
            NoteBlockMechanicFactory.commitNativeMappings();

            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.registerBlockstateVariant("instrument=snare,note=1,powered=false",
                    NoteBlockMechanicFactory.getModelJson(stagedModel));
            NoteBlockMechanicFactory.discardNativeMappings();

            String currentBlockstate = NoteBlockMechanicFactory.getBlockstateContent();
            assertTrue(currentBlockstate.contains(activeModel));
            assertFalse(currentBlockstate.contains(stagedModel));
        } finally {
            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.commitNativeMappings();
            NoteBlockMechanicFactory.discardNativeMappings();
        }
    }

    @Test
    void replacingFactoryBeforeAFailedReloadKeepsPublishedBlockstateMappings() {
        String activeModel = "oraxen:block/current_native_block";

        try {
            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.registerBlockstateVariant("instrument=basedrum,note=1,powered=false",
                    NoteBlockMechanicFactory.getModelJson(activeModel));
            NoteBlockMechanicFactory.commitNativeMappings();

            OraxenPlugin plugin = mock(OraxenPlugin.class);
            ConfigsManager configs = mock(ConfigsManager.class);
            when(plugin.getConfigsManager()).thenReturn(configs);
            when(configs.getMechanics()).thenReturn(mock(YamlConfiguration.class));
            try (var oraxen = mockStatic(OraxenPlugin.class)) {
                oraxen.when(OraxenPlugin::get).thenReturn(plugin);
                new NoteBlockMechanicFactory(mock(ConfigurationSection.class), false);
            }
            NoteBlockMechanicFactory.clearInstance(NoteBlockMechanicFactory.getInstance());

            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.registerBlockstateVariant("instrument=snare,note=1,powered=false",
                    NoteBlockMechanicFactory.getModelJson("oraxen:block/failed_reload_model"));
            NoteBlockMechanicFactory.discardNativeMappings();

            assertTrue(NoteBlockMechanicFactory.getBlockstateContent().contains(activeModel));
            assertFalse(NoteBlockMechanicFactory.getBlockstateContent().contains("failed_reload_model"));
        } finally {
            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.commitNativeMappings();
            NoteBlockMechanicFactory.discardNativeMappings();
        }
    }
}
