package io.th0rgal.oraxen.commands;

import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.packets.NativePacketAdapter;
import io.th0rgal.oraxen.packets.PacketAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class ReportCommand {

    OraxenCommand getReportCommand() {
        return new OraxenCommand("report")
            .withPermission("oraxen.command.report")
            .executes((sender, args) -> {
                // Get Oraxen version
                String oraxenVersion = OraxenPlugin.get().getPluginMeta().getVersion();

                // Get Protocol Library version
                PacketAdapter packetAdapter = OraxenPlugin.get().getPacketAdapter();
                Plugin protocolPlugin = packetAdapter.getPlugin();
                String protocolLibVersion = packetAdapter instanceof NativePacketAdapter
                    ? "Native"
                    : protocolPlugin != null ? protocolPlugin.getName() + "-" + protocolPlugin.getPluginMeta().getVersion()
                    : "Not installed";

                // Get server info
                String serverSoftware = Bukkit.getName();
                String serverVersion = Bukkit.getVersion();

                // Get OS info
                String osName = System.getProperty("os.name");
                String osVersion = System.getProperty("os.version");
                String osArch = System.getProperty("os.arch");

                // Format report
                String report = String.format("""
                        
                        ### System Report
                        **Plugin Versions:**
                        - Oraxen: %s
                        - ProtocolAPI: %s
                        
                        **Server Information:**
                        - Software: %s
                        - Version: %s
                        
                        **System Information:**
                        - OS: %s
                        - OS Version: %s
                        - Architecture: %s
                        """,
                    oraxenVersion,
                    protocolLibVersion,
                    serverSoftware,
                    serverVersion,
                    osName,
                    osVersion,
                    osArch);

                // Send report to sender
                sender.sendPlainMessage(report);
            });
    }
}
