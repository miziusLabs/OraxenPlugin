package io.th0rgal.oraxen.mechanics.provided.gameplay.noteblock;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class NoteBlockMechanicFactorySnapshotTest {

    @Test
    void itemLookupKeepsPublishedMechanicUntilReplacementSetCommits() {
        String itemId = "snapshot_test_" + UUID.randomUUID();
        NoteBlockMechanic original = mock(NoteBlockMechanic.class);
        NoteBlockMechanic replacement = mock(NoteBlockMechanic.class);

        try {
            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.registerItemMechanic(itemId, original);
            NoteBlockMechanicFactory.commitNativeMappings();
            assertSame(original, NoteBlockMechanicFactory.getItemMechanic(itemId));

            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.registerItemMechanic(itemId, replacement);
            assertSame(original, NoteBlockMechanicFactory.getItemMechanic(itemId));

            NoteBlockMechanicFactory.commitNativeMappings();
            assertSame(replacement, NoteBlockMechanicFactory.getItemMechanic(itemId));

            NoteBlockMechanicFactory.beginNativeMappingsReload();
            NoteBlockMechanicFactory.registerItemMechanic(itemId, original);
            NoteBlockMechanicFactory.discardNativeMappings();
            assertSame(replacement, NoteBlockMechanicFactory.getItemMechanic(itemId));
        } finally {
            NoteBlockMechanicFactory.discardNativeMappings();
        }
    }
}
