package io.th0rgal.oraxen.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperConfigUpdaterTest {
    @Test
    void restorationChangesOnlyTheActiveNoteSettingAndPreservesComments() {
        List<String> original = List.of(
                "# disable-noteblock-updates: true",
                "block-updates: # backing blocks",
                "  disable-noteblock-updates: true # keep vanilla music",
                "  disable-tripwire-updates: true",
                "  disable-chorus-plant-updates: true",
                "other:",
                "  disable-noteblock-updates: true");
        List<String> expected = List.of(
                "# disable-noteblock-updates: true",
                "block-updates: # backing blocks",
                "  disable-noteblock-updates: false # keep vanilla music",
                "  disable-tripwire-updates: true",
                "  disable-chorus-plant-updates: true",
                "other:",
                "  disable-noteblock-updates: true");

        assertEquals(expected, PaperConfigUpdater.restoreNoteBlockUpdates(original));
        assertEquals(expected, PaperConfigUpdater.restoreNoteBlockUpdates(expected));
        assertEquals("  disable-noteblock-updates: true # keep vanilla music", original.get(2));
    }

    @Test
    void restorationAddsMissingSettingWithExistingSectionIndentation() {
        assertEquals(List.of("block-updates:", "    disable-noteblock-updates: false",
                        "    disable-tripwire-updates: true", "other: true"),
                PaperConfigUpdater.restoreNoteBlockUpdates(List.of("block-updates:",
                        "    disable-tripwire-updates: true", "other: true")));
    }

    @Test
    void restorationAddsMissingRootSectionWithoutChangingUnrelatedNestedSection() {
        List<String> original = List.of("other:", "  block-updates:", "    disable-noteblock-updates: true");
        assertEquals(List.of("other:", "  block-updates:", "    disable-noteblock-updates: true", "",
                        "block-updates:", "  disable-noteblock-updates: false"),
                PaperConfigUpdater.restoreNoteBlockUpdates(original));
    }

    @Test
    void restorationExpandsEmptyMappingButRejectsPopulatedInlineMappingWithoutWriting() {
        assertEquals(List.of("block-updates: # defaults", "  disable-noteblock-updates: false"),
                PaperConfigUpdater.restoreNoteBlockUpdates(List.of("block-updates: {} # defaults")));
        assertThrows(IllegalArgumentException.class, () -> PaperConfigUpdater.restoreNoteBlockUpdates(
                List.of("block-updates: {disable-tripwire-updates: true}")));
    }
}
