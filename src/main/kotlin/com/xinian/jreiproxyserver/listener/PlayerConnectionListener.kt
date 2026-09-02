package com.xinian.jreiproxyserver.listener

import com.xinian.jreiproxyserver.JReiProxyServer
import com.xinian.jreiproxyserver.network.Channels
import io.papermc.paper.event.server.ServerResourcesReloadedEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import java.util.UUID

/**
 * Decides *when* a player is sent the recipes.
 *
 * A client announces its recipe-viewer channels shortly after it finishes logging in, so the join
 * event itself is too early to tell whether the player is even running one. The registration event
 * is the real signal; the delayed pass from join only exists for NeoForge clients, which negotiate
 * their channels in the configuration phase and so announce nothing a plugin can see.
 */
class PlayerConnectionListener(private val plugin: JReiProxyServer) : Listener {

    private val synced = HashSet<UUID>()
    private val scheduled = HashSet<UUID>()

    @EventHandler
    fun onRegisterChannel(event: PlayerRegisterChannelEvent) {
        if (!isViewerChannel(event.channel)) return
        scheduleSync(event.player, CHANNEL_SETTLE_TICKS)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        scheduleSync(event.player, JOIN_FALLBACK_TICKS)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        synced.remove(event.player.uniqueId)
        scheduled.remove(event.player.uniqueId)
        plugin.packetListener.onPlayerQuit(event.player)
        plugin.playerResyncCommand.forget(event.player)
    }

    /** A datapack reload replaces every recipe, so everything cached and everything sent is stale. */
    @EventHandler
    fun onResourcesReloaded(event: ServerResourcesReloadedEvent) {
        if (!plugin.pluginConfig.syncOnDatapackReload) return
        plugin.rebuildRecipeCache()
        synced.clear()
        plugin.server.onlinePlayers.forEach { plugin.recipeSyncService.sync(it) }
    }

    fun forgetSync(player: Player) {
        synced.remove(player.uniqueId)
    }

    private fun scheduleSync(player: Player, delayTicks: Long) {
        if (!plugin.pluginConfig.syncEnabled || !plugin.pluginConfig.syncOnJoin) return

        val id = player.uniqueId
        if (id in synced || !scheduled.add(id)) return

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            scheduled.remove(id)
            if (!player.isOnline || id in synced) return@Runnable

            val result = plugin.recipeSyncService.sync(player)
            if (result.sentAnything) {
                synced.add(id)
            }
        }, delayTicks)
    }

    private fun isViewerChannel(channel: String): Boolean =
        channel == Channels.Loader.FABRIC_RECIPE_SYNC ||
            channel == Channels.Loader.NEOFORGE_RECIPE_CONTENT ||
            channel.startsWith(Channels.Rei.NAMESPACE) ||
            channel.startsWith("jei:")

    companion object {
        /** Long enough for the rest of a client's channel registrations to arrive. */
        private const val CHANNEL_SETTLE_TICKS = 20L

        /** Covers clients that register nothing the server can see, such as NeoForge. */
        private const val JOIN_FALLBACK_TICKS = 60L
    }
}
