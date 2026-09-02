package gg.nekohosting.vanilla.jreiproxyserver.command

import gg.nekohosting.vanilla.jreiproxyserver.JReiProxyServer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

/**
 * `/jei` and `/rei` — lets a player ask for the recipes again, without any permission.
 *
 * The recipes are pushed a moment after join, which is the one point where a slow or interrupted
 * login can lose them: the payload goes out before the client has finished registering its
 * channels, and nothing on the server can tell that it landed. Rather than have players relog, this
 * gives them a way to ask again.
 */
class PlayerResyncCommand(private val plugin: JReiProxyServer) : CommandExecutor, TabCompleter {

    /** When each player last asked, so a resend cannot be used to flood the server. */
    private val lastUse = HashMap<UUID, Long>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(plugin.localeManager.component("command.player-only"))
            return true
        }

        if (args.isNotEmpty() && args[0].lowercase() !in SUBCOMMANDS) {
            player.sendMessage(plugin.localeManager.component("command.resync.self-usage", label))
            return true
        }

        if (!plugin.pluginConfig.syncEnabled) {
            player.sendMessage(plugin.localeManager.component("command.resync.disabled"))
            return true
        }

        // Each resend is the whole recipe set plus the recipe book, so it is not free.
        val cooldown = plugin.pluginConfig.playerResyncCooldownSeconds * 1000L
        val exempt = player.hasPermission("jreiproxyserver.admin")
        val since = System.currentTimeMillis() - (lastUse[player.uniqueId] ?: 0L)
        if (!exempt && cooldown > 0 && since < cooldown) {
            val remaining = ((cooldown - since) + 999) / 1000
            player.sendMessage(plugin.localeManager.component("command.resync.cooldown", remaining))
            return true
        }
        lastUse[player.uniqueId] = System.currentTimeMillis()

        plugin.connectionListener.forgetSync(player)
        val result = plugin.recipeSyncService.sync(player)
        if (result.sentAnything) {
            player.sendMessage(
                plugin.localeManager.component("command.resync.self", result.recipesSent, result.recipeBookEntriesSent)
            )
        } else {
            player.sendMessage(plugin.localeManager.component("command.resync.self-nothing"))
        }
        return true
    }

    fun forget(player: Player) {
        lastUse.remove(player.uniqueId)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): List<String> =
        if (args.size == 1) SUBCOMMANDS.filter { it.startsWith(args[0], ignoreCase = true) } else emptyList()

    private companion object {
        val SUBCOMMANDS = listOf("resync", "retry")
    }
}
