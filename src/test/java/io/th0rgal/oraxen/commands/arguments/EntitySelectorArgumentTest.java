package io.th0rgal.oraxen.commands.arguments;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class EntitySelectorArgumentTest {

    @Test
    void playerSelectorsStillResolveWhenVanillaSelectorsAreUnsupported() {
        Player sender = mock(Player.class);
        Player other = mock(Player.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.selectEntities(any(CommandSender.class), anyString()))
                    .thenThrow(new UnsupportedOperationException());
            bukkit.when(Bukkit::getOnlinePlayers).then(invocation -> List.of(sender, other));

            OraxenArgument.ParseResult<Collection<Player>> all =
                    new EntitySelectorArgument.ManyPlayers("targets").parse(sender, List.of("@a"), 0);
            OraxenArgument.ParseResult<Player> self =
                    new EntitySelectorArgument.OnePlayer("target").parse(sender, List.of("@s"), 0);

            assertTrue(all.success());
            assertEquals(List.of(sender, other), List.copyOf(all.value()));
            assertTrue(self.success());
            assertEquals(sender, self.value());
        }
    }
}
