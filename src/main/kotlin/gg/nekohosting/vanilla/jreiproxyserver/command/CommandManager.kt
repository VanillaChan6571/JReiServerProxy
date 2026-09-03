package gg.nekohosting.vanilla.jreiproxyserver.command

import gg.nekohosting.vanilla.jreiproxyserver.JReiProxyServer
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class CommandManager(private val plugin: JReiProxyServer) : CommandExecutor, TabCompleter {

    private val localeManager get() = plugin.localeManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("jreiproxyserver.admin")) {
            sender.sendMessage(localeManager.component("command.no-permission"))
            return true
        }

        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                plugin.reloadPluginConfig()
                sender.sendMessage(localeManager.component("command.reload-success"))
            }

            "resync" -> resync(sender, args)
            "info" -> info(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun resync(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(localeManager.component("command.resync.usage"))
            return
        }

        if (args[1].equals("all", ignoreCase = true)) {
            // Each player is resynced on the region thread that owns them, so the count is
            // reported as the number scheduled rather than the number that succeeded.
            val players = Bukkit.getOnlinePlayers().toList()
            for (player in players) {
                plugin.connectionListener.forgetSync(player)
                player.scheduler.run(plugin, { plugin.recipeSyncService.sync(player) }, null)
            }
            sender.sendMessage(localeManager.component("command.resync.all", players.size))
            return
        }

        val player = Bukkit.getPlayer(args[1])
        if (player == null) {
            sender.sendMessage(localeManager.component("command.player-not-found", args[1]))
            return
        }

        plugin.connectionListener.forgetSync(player)
        val result = plugin.recipeSyncService.sync(player)
        if (result.sentAnything) {
            sender.sendMessage(localeManager.component("command.resync.player", player.name))
        } else {
            sender.sendMessage(localeManager.component("command.resync.skipped", player.name))
        }
    }

    private fun info(sender: CommandSender) {
        val cache = plugin.recipeCache
        val service = plugin.recipeSyncService

        sender.sendMessage(localeManager.component("command.info.header"))
        if (!plugin.pluginConfig.syncEnabled) {
            sender.sendMessage(localeManager.component("command.info.disabled"))
        }
        sender.sendMessage(localeManager.component("command.info.recipes", cache.recipeCount, cache.blacklistedCount))
        sender.sendMessage(
            localeManager.component(
                "command.info.payloads",
                cache.fabricPayload.kilobytes,
                cache.neoForgePayload.kilobytes,
            )
        )
        sender.sendMessage(
            localeManager.component(
                "command.info.recipe-book",
                cache.recipeBookEntries,
                cache.recipeBookAddPackets.size,
                plugin.pluginConfig.recipeBookMode.name.lowercase(),
            )
        )
        sender.sendMessage(
            localeManager.component(
                "command.info.rei-displays",
                cache.reiDisplayPayload.displays,
                cache.reiDisplayPayload.kilobytes,
                cache.reiDisplayPayload.serializers,
            )
        )
        sender.sendMessage(
            localeManager.component(
                "command.info.players",
                service.fabricSynced,
                service.neoForgeSynced,
                service.recipeBookSynced,
            )
        )
        sender.sendMessage(localeManager.component("command.info.rei-players", service.reiDisplaySynced))
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(localeManager.component("command.help-header"))
        sender.sendMessage(localeManager.component("command.help-reload"))
        sender.sendMessage(localeManager.component("command.help-resync"))
        sender.sendMessage(localeManager.component("command.help-info"))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): List<String> {
        if (!sender.hasPermission("jreiproxyserver.admin")) return emptyList()

        return when (args.size) {
            1 -> listOf("reload", "resync", "info").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("resync", ignoreCase = true)) {
                (listOf("all") + Bukkit.getOnlinePlayers().map { it.name })
                    .filter { it.startsWith(args[1], ignoreCase = true) }
            } else {
                emptyList()
            }

            else -> emptyList()
        }
    }
}
