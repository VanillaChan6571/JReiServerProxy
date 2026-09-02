package com.xinian.jreiproxyserver.command

import com.xinian.jreiproxyserver.JReiProxyServer
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class CommandManager(private val plugin: JReiProxyServer) : CommandExecutor, TabCompleter {

    private val localeManager get() = plugin.localeManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("jreiproxyserver.admin")) {
            sender.sendMessage(localeManager.getMessage("command.no-permission"))
            return true
        }

        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                plugin.reloadPluginConfig()
                sender.sendMessage(localeManager.getMessage("command.reload-success"))
            }

            "resync" -> resync(sender, args)
            "info" -> info(sender)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun resync(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(localeManager.getMessage("command.resync.usage"))
            return
        }

        if (args[1].equals("all", ignoreCase = true)) {
            var count = 0
            for (player in Bukkit.getOnlinePlayers()) {
                plugin.connectionListener.forgetSync(player)
                if (plugin.recipeSyncService.sync(player).sentAnything) count++
            }
            sender.sendMessage(localeManager.getMessage("command.resync.all", count))
            return
        }

        val player = Bukkit.getPlayer(args[1])
        if (player == null) {
            sender.sendMessage(localeManager.getMessage("command.player-not-found", args[1]))
            return
        }

        plugin.connectionListener.forgetSync(player)
        val result = plugin.recipeSyncService.sync(player)
        if (result.sentAnything) {
            sender.sendMessage(localeManager.getMessage("command.resync.player", player.name))
        } else {
            sender.sendMessage(localeManager.getMessage("command.resync.skipped", player.name))
        }
    }

    private fun info(sender: CommandSender) {
        val cache = plugin.recipeCache
        val service = plugin.recipeSyncService

        sender.sendMessage(localeManager.getMessage("command.info.header"))
        if (!plugin.pluginConfig.syncEnabled) {
            sender.sendMessage(localeManager.getMessage("command.info.disabled"))
        }
        sender.sendMessage(localeManager.getMessage("command.info.recipes", cache.recipeCount, cache.blacklistedCount))
        sender.sendMessage(
            localeManager.getMessage(
                "command.info.payloads",
                cache.fabricPayload.kilobytes,
                cache.neoForgePayload.kilobytes,
            )
        )
        sender.sendMessage(
            localeManager.getMessage(
                "command.info.recipe-book",
                cache.recipeBookEntries,
                cache.recipeBookAddPackets.size,
                plugin.pluginConfig.recipeBookMode.name.lowercase(),
            )
        )
        sender.sendMessage(
            localeManager.getMessage(
                "command.info.players",
                service.fabricSynced,
                service.neoForgeSynced,
                service.recipeBookSynced,
            )
        )
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(localeManager.getMessage("command.help-header"))
        sender.sendMessage(localeManager.getMessage("command.help-reload"))
        sender.sendMessage(localeManager.getMessage("command.help-resync"))
        sender.sendMessage(localeManager.getMessage("command.help-info"))
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
