package io.th0rgal.oraxen.commands;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.commands.arguments.ArgumentSuggestions;
import io.th0rgal.oraxen.commands.arguments.TextArgument;
import io.th0rgal.oraxen.converter.NexoConverter;
import io.th0rgal.oraxen.utils.SchedulerUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConvertCommand {
    private final AtomicBoolean running = new AtomicBoolean();

    public OraxenCommand getConvertCommand() {
        return new OraxenCommand("convert")
                .withPermission("oraxen.command.convert")
                .withArguments(new TextArgument("format").replaceSuggestions(ArgumentSuggestions.strings("NEXO")))
                .executes((sender, args) -> {
                    if (!"NEXO".equalsIgnoreCase(String.valueOf(args.get("format")))) {
                        sender.sendPlainMessage("Supported format is NEXO. Use /oraxen convert NEXO.");
                        return;
                    }
                    if (!running.compareAndSet(false, true)) {
                        sender.sendPlainMessage("A conversion is already running.");
                        return;
                    }
                    Path converter = OraxenPlugin.get().getDataFolder().toPath().resolve("converter");
                    sender.sendPlainMessage("Converting the Nexo setup. The source and live Oraxen setup will not change.");
                    SchedulerUtil.runTaskAsync(() -> {
                        try {
                            NexoConverter.Result result = new NexoConverter().convert(converter.resolve("Nexo"), converter.resolve("output"));
                            reply(sender, "Converted " + result.files() + " files to plugins/Oraxen/converter/output with "
                                    + result.warnings().size() + " warnings. Review conversion-report.txt before importing.");
                        } catch (Exception e) {
                            reply(sender, "Nexo conversion failed. " + e.getMessage());
                        } finally {
                            running.set(false);
                        }
                    });
                });
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) SchedulerUtil.runForEntity(player, () -> player.sendPlainMessage(message));
        else SchedulerUtil.runTask(() -> sender.sendPlainMessage(message));
    }
}
