package gg.nekohosting.vanilla.jreiproxyserver.listener

import gg.nekohosting.vanilla.jreiproxyserver.JReiProxyServer
import gg.nekohosting.vanilla.jreiproxyserver.network.Channels
import io.papermc.paper.event.server.ServerResourcesReloadedEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides *when* a player is sent the recipes.
 *
 * A client announces its recipe-viewer channels shortly after it finishes logging in, so the join
 * event itself is too early to tell whether the player is even running one. The registration event
 * is the real signal; the delayed pass from join only exists for NeoForge clients, which negotiate
 * their channels in the configuration phase and so announce nothing a plugin can see.
 */
class PlayerConnectionListener(private val plugin: JReiProxyServer) : Listener {

    // Folia runs each player on the region thread owning them, so join, quit and channel
    // registration for different players land on different threads at once.
    private val synced = ConcurrentHashMap.newKeySet<UUID>()
    private val scheduled = ConcurrentHashMap.newKeySet<UUID>()

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
        // Each player must be touched on the region thread that owns them.
        plugin.server.onlinePlayers.forEach { player ->
            player.scheduler.run(plugin, { plugin.recipeSyncService.sync(player) }, null)
        }
    }

    fun forgetSync(player: Player) {
        synced.remove(player.uniqueId)
    }

    private fun scheduleSync(player: Player, delayTicks: Long) {
        if (!plugin.pluginConfig.syncEnabled || !plugin.pluginConfig.syncOnJoin) return

        val id = player.uniqueId
        if (id in synced || !scheduled.add(id)) return

        // The entity scheduler, rather than the Bukkit one: it exists on Paper and is the only
        // form Folia supports, where it runs on whichever region owns the player. The retired
        // callback fires if they disconnect before the delay elapses.
        player.scheduler.runDelayed(
            plugin,
            {
                scheduled.remove(id)
                if (player.isOnline && id !in synced && plugin.recipeSyncService.sync(player).sentAnything) {
                    synced.add(id)
                }
            },
            { scheduled.remove(id) },
            delayTicks,
        )
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
