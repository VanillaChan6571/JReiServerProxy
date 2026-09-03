package gg.nekohosting.vanilla.jreiproxyserver.recipe

import gg.nekohosting.vanilla.jreiproxyserver.JReiProxyServer
import gg.nekohosting.vanilla.jreiproxyserver.config.RecipeBookMode
import gg.nekohosting.vanilla.jreiproxyserver.network.Channels
import gg.nekohosting.vanilla.jreiproxyserver.network.rei.ReiSplitPacketFramer
import gg.nekohosting.vanilla.jreiproxyserver.nms.minecraftServer
import gg.nekohosting.vanilla.jreiproxyserver.nms.nms
import gg.nekohosting.vanilla.jreiproxyserver.nms.sendPacket
import gg.nekohosting.vanilla.jreiproxyserver.nms.sendRawPayload
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket
import net.minecraft.tags.TagNetworkSerialization
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicInteger

/** Which loader channel a client can receive recipes on. */
enum class LoaderChannel {
    FABRIC,
    NEOFORGE,

    /** No channel the server can send recipes on; the client keeps its own built-in recipes. */
    NONE,
}

/** What one sync attempt actually did, for logging and `/jeiproxy info`. */
data class SyncResult(
    val channel: LoaderChannel,
    val recipesSent: Int,
    val recipeBookEntriesSent: Int,
    val reiDisplaysSent: Int = 0,
) {
    val sentAnything: Boolean get() = recipesSent > 0 || recipeBookEntriesSent > 0 || reiDisplaysSent > 0
}

/**
 * Decides what each player gets and sends it.
 *
 * Since Minecraft 1.21.2 the vanilla server no longer sends recipe data to clients, so a recipe
 * viewer on a plugin server falls back to the recipes shipped with the client. Each mod loader
 * added its own replacement channel; this puts the server's recipes back on whichever one the
 * player's client can read.
 */
class RecipeSyncService(
    private val plugin: JReiProxyServer,
    private val cache: RecipeCache,
) {

    // Counted from every region thread that syncs a player, so plain ints would drop increments.
    private val fabricCount = AtomicInteger()
    private val neoForgeCount = AtomicInteger()
    private val recipeBookCount = AtomicInteger()
    private val reiDisplayCount = AtomicInteger()

    val fabricSynced: Int get() = fabricCount.get()
    val neoForgeSynced: Int get() = neoForgeCount.get()
    val recipeBookSynced: Int get() = recipeBookCount.get()
    val reiDisplaySynced: Int get() = reiDisplayCount.get()

    /**
     * Whether the player is running something that wants recipes.
     *
     * A recipe viewer registers its own plugin channels on join, which is the only thing a plugin
     * can see of it.
     */
    fun hasRecipeViewer(player: Player): Boolean {
        val channels = player.listeningPluginChannels
        return channels.any { it.startsWith("jei:") || it.startsWith(Channels.Rei.NAMESPACE) } ||
            loaderChannel(player) != LoaderChannel.NONE
    }

    fun hasReiChannels(player: Player): Boolean =
        player.listeningPluginChannels.any { it.startsWith(Channels.Rei.NAMESPACE) }

    private fun canReceiveReiDisplays(player: Player): Boolean =
        Channels.Rei.SYNC_DISPLAYS in player.listeningPluginChannels

    fun loaderChannel(player: Player): LoaderChannel {
        val channels = player.listeningPluginChannels
        if (Channels.Loader.FABRIC_RECIPE_SYNC in channels) return LoaderChannel.FABRIC
        if (Channels.Loader.NEOFORGE_RECIPE_CONTENT in channels) return LoaderChannel.NEOFORGE

        // Neither loader is obliged to announce its recipe channel through minecraft:register —
        // NeoForge negotiates in the configuration phase, and Fabric only announces a channel it
        // has a receiver for, which depends on what the client's mods opted into. The brand string
        // is the only other thing the server is told. Guessing wrong is harmless: a client that
        // cannot read the channel discards the payload.
        val brand = player.clientBrandName ?: return LoaderChannel.NONE
        return when {
            brand.contains("neoforge", ignoreCase = true) -> LoaderChannel.NEOFORGE
            brand.contains("fabric", ignoreCase = true) -> LoaderChannel.FABRIC
            else -> LoaderChannel.NONE
        }
    }

    private fun shouldSendReiDisplays(player: Player): Boolean =
        plugin.isReiDisplaySyncEnabled() && canReceiveReiDisplays(player)

    private fun shouldSendRecipeBook(player: Player): Boolean {
        if (shouldSendReiDisplays(player)) return false
        return when (plugin.pluginConfig.recipeBookMode) {
            RecipeBookMode.OFF -> false
            RecipeBookMode.ALL -> hasRecipeViewer(player)
            RecipeBookMode.AUTO -> hasReiChannels(player)
        }
    }

    /** Sends the server's recipes to one player. Must run on the main thread. */
    fun sync(player: Player): SyncResult {
        val config = plugin.pluginConfig
        if (!config.syncEnabled) return SyncResult(LoaderChannel.NONE, 0, 0)

        val handle = player.nms
        val channel = loaderChannel(player)
        var recipesSent = 0

        if (config.debug) {
            val viewerChannels = player.listeningPluginChannels
                .filter {
                    it.startsWith("jei:") || it.startsWith(Channels.Rei.NAMESPACE) ||
                        it.startsWith("fabric:") || it.startsWith("neoforge:")
                }
                .sorted()
            plugin.logger.info(
                "[debug] ${player.name}: brand=${player.clientBrandName}, channel=$channel, " +
                    "recipeBook=${shouldSendRecipeBook(player)}, reiDisplays=${shouldSendReiDisplays(player)}, " +
                    "registered=$viewerChannels"
            )
        }

        when (channel) {
            LoaderChannel.FABRIC -> {
                val payload = cache.fabricPayload
                if (payload.recipes > 0) {
                    handle.sendRawPayload(Channels.Loader.FABRIC_RECIPE_SYNC, payload.bytes)
                    recipesSent = payload.recipes
                    fabricCount.incrementAndGet()
                    if (config.debug) {
                        plugin.logger.info(
                            plugin.localeManager.plain(
                                "sync.sent-fabric", payload.recipes, payload.kilobytes, player.name,
                            )
                        )
                    }
                }
            }

            LoaderChannel.NEOFORGE -> {
                val payload = cache.neoForgePayload
                if (payload.recipes > 0) {
                    // Tags first: the holder sets inside these recipes name tags the client
                    // resolves while decoding them.
                    handle.sendPacket(buildTagsPacket())
                    handle.sendRawPayload(Channels.Loader.NEOFORGE_RECIPE_CONTENT, payload.bytes)
                    recipesSent = payload.recipes
                    neoForgeCount.incrementAndGet()
                    if (config.debug) {
                        plugin.logger.info(
                            plugin.localeManager.plain(
                                "sync.sent-neoforge", payload.recipes, payload.kilobytes, player.name,
                            )
                        )
                    }
                }
            }

            LoaderChannel.NONE -> {
                if (config.debug) {
                    plugin.logger.info(plugin.localeManager.plain("sync.no-channel", player.name))
                }
            }
        }

        var recipeBookSent = 0
        var reiDisplaysSent = 0
        if (shouldSendReiDisplays(player)) {
            val payload = cache.reiDisplayPayload
            val frames = ReiSplitPacketFramer.frame(Channels.Rei.SYNC_DISPLAYS, payload.bytes)
            frames.forEach { handle.sendRawPayload(Channels.Rei.SYNC_DISPLAYS, it) }
            reiDisplaysSent = payload.displays
            reiDisplayCount.incrementAndGet()
            if (config.debug) {
                plugin.logger.info(
                    plugin.localeManager.plain(
                        "sync.sent-rei-displays",
                        payload.displays,
                        payload.kilobytes,
                        frames.size,
                        player.name,
                    )
                )
            }
        } else if (shouldSendRecipeBook(player) && cache.recipeBookAddPackets.isNotEmpty()) {
            // Recipes and recipe-book entries both name item tags, which the client resolves as it
            // decodes them and cannot recover from missing. Refreshing the tag set first means every
            // tag any recipe mentions is already known, rather than only those vanilla had sent.
            handle.sendPacket(buildTagsPacket())
            cache.recipeBookRemovePackets.forEach(handle::sendPacket)
            cache.recipeBookAddPackets.forEach(handle::sendPacket)
            recipeBookSent = cache.recipeBookEntries
            recipeBookCount.incrementAndGet()
            if (config.debug) {
                plugin.logger.info(
                    plugin.localeManager.plain("sync.sent-recipe-book", recipeBookSent, player.name)
                )
            }
        }

        // Order is load-bearing: this makes the client rebuild its recipe container from what it
        // currently holds, so the payload above has to have been handled by the time it arrives.
        // Sending it first would carry the old, empty recipe set forward.
        // DisplaySyncPacket schedules its own REI registry update. This vanilla packet is only the
        // nudge needed after loader or recipe-book data and can race REI's queued display job.
        if (config.triggerRecipeUpdate && (recipesSent > 0 || recipeBookSent > 0)) {
            handle.sendPacket(cache.buildRecipeUpdatePacket())
        }

        val result = SyncResult(channel, recipesSent, recipeBookSent, reiDisplaysSent)

        // JEI has already printed "this server does not provide recipes" by now — it builds its
        // list the moment the vanilla recipe packet arrives, before a plugin may send anything.
        // This line is what tells the player that warning is out of date.
        if (config.notifyPlayer && result.sentAnything) {
            player.sendMessage(plugin.localeManager.component("sync.player-notice"))
        }

        return result
    }

    private fun buildTagsPacket(): ClientboundUpdateTagsPacket =
        ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(minecraftServer.registries()))
}
