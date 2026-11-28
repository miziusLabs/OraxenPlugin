package io.th0rgal.oraxen.commands;

import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockState;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class MigrateStringBlockCommand {

    OraxenCommand getMigrateStringBlockCommand() {
        return new OraxenCommand("migrate-string-block")
                .withPermission("oraxen.command.migrate-string-block")
                .executesPlayer((player, args) -> {
                    Block target = player.getTargetBlockExact(6, FluidCollisionMode.NEVER);
                    if (target == null) {
                        player.sendPlainMessage("Look at a confirmed legacy custom string block within 6 blocks.");
                        return;
                    }
                    migrateTarget(player, target);
                });
    }

    private void migrateTarget(Player player, Block target) {
        if (!Bukkit.isOwnedByCurrentRegion(target)) {
            SchedulerUtil.runAtLocation(target.getLocation(), () -> migrateTarget(player, target));
            return;
        }

        boolean migrated = StringBlockState.migrateLegacy(target);
        SchedulerUtil.runForEntity(player, () -> {
            if (migrated) {
                player.sendPlainMessage("Migrated the targeted legacy custom string block. Tall blocks can also convert their old upper half.");
            } else {
                player.sendPlainMessage("No confirmed legacy custom string block was migrated. Target the exact old custom block; vanilla tripwire is never converted automatically.");
            }
        });
    }
}
