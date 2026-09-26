package io.th0rgal.oraxen.mechanics.provided.gameplay.block;

import io.th0rgal.oraxen.compatibilities.CompatibilitiesManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class BlockEventsTest {

    @Test
    void opPlayerDispatchesAsRealPlayerAndRestoresOp() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("testPlayer");
        BlockEvents events = opPlayerEvents();

        try (MockedStatic<CompatibilitiesManager> compatibilities = mockStatic(CompatibilitiesManager.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.dispatchCommand(player, "say hello")).thenAnswer(invocation -> {
                assertSame(player, invocation.getArgument(0));
                verify(player).setOp(true);
                return true;
            });

            events.run(player, Action.RIGHT_CLICK_BLOCK);

            bukkit.verify(() -> Bukkit.dispatchCommand(player, "say hello"));
            verify(player).setOp(false);
        }
    }

    @Test
    void opPlayerRestoresOpWhenCommandThrows() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("testPlayer");
        BlockEvents events = opPlayerEvents();

        try (MockedStatic<CompatibilitiesManager> compatibilities = mockStatic(CompatibilitiesManager.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.dispatchCommand(player, "say hello"))
                    .thenThrow(new IllegalArgumentException("command failed"));

            assertThrows(IllegalArgumentException.class, () -> events.run(player, Action.RIGHT_CLICK_BLOCK));

            verify(player).setOp(true);
            verify(player).setOp(false);
        }
    }

    private static BlockEvents opPlayerEvents() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("events", List.of(Map.of(
                "click", "RIGHT",
                "actions", List.of(Map.of("command", "say hello", "executor", "OP-PLAYER"))
        )));
        return new BlockEvents(config, "test", "furniture.events");
    }
}
