package io.th0rgal.oraxen.commands;

import io.th0rgal.oraxen.utils.SchedulerUtil;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class ReloadCommandTest {

    @Test
    void sendsPlayerReloadRepliesOnThePlayersEntityThread() {
        Player player = mock(Player.class);

        try (var scheduler = mockStatic(SchedulerUtil.class)) {
            ReloadCommand.sendReloadMessage(player, "items");

            scheduler.verify(() -> SchedulerUtil.runForEntity(eq(player), any(Runnable.class)));
        }
    }
}
