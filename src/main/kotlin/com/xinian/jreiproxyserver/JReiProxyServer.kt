package com.xinian.jreiproxyserver

import com.xinian.jreiproxyserver.command.CommandManager
import com.xinian.jreiproxyserver.command.PlayerResyncCommand
import com.xinian.jreiproxyserver.config.PluginConfig
import com.xinian.jreiproxyserver.config.ResourceBackfill
import com.xinian.jreiproxyserver.i18n.LocaleManager
import com.xinian.jreiproxyserver.listener.PlayerConnectionListener
import com.xinian.jreiproxyserver.network.Channels
import com.xinian.jreiproxyserver.network.ViewerPacketListener
import com.xinian.jreiproxyserver.recipe.RecipeCache
import com.xinian.jreiproxyserver.recipe.RecipeSyncService
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Serves a Paper server's recipes to JEI and REI clients, and answers the packets those mods send.
 *
 * Since Minecraft 1.21.2 the vanilla server no longer sends recipe data to clients, and both mods
 * expect their own server-side half to exist — neither of which a plugin server gets for free.
 */
class JReiProxyServer : JavaPlugin() {

    lateinit var pluginConfig: PluginConfig
        private set

    lateinit var localeManager: LocaleManager
        private set

    lateinit var recipeCache: RecipeCache
        private set

    lateinit var recipeSyncService: RecipeSyncService
        private set

    lateinit var connectionListener: PlayerConnectionListener
        private set

    lateinit var packetListener: ViewerPacketListener
        private set

    lateinit var playerResyncCommand: PlayerResyncCommand
        private set

    override fun onEnable() {
        saveDefaultConfig()
        // saveResource warns when the file is already there, and on any restart after the first it
        // always is; backfillResources below is what keeps an existing file current.
        for (name in BUNDLED_LANG) {
            if (!File(dataFolder, name).exists()) saveResource(name, false)
        }
        backfillResources()

        reloadConfig()
        pluginConfig = PluginConfig.from(config)
        localeManager = LocaleManager(this)

        recipeCache = RecipeCache()
        recipeSyncService = RecipeSyncService(this, recipeCache)
        connectionListener = PlayerConnectionListener(this)

        registerChannels()
        server.pluginManager.registerEvents(connectionListener, this)
        getCommand("jreiproxy")?.let {
            val commands = CommandManager(this)
            it.setExecutor(commands)
            it.tabCompleter = commands
        }
        playerResyncCommand = PlayerResyncCommand(this)
        getCommand("jei")?.let {
            it.setExecutor(playerResyncCommand)
            it.tabCompleter = playerResyncCommand
        }

        logger.info(localeManager.getMessage("plugin.decor"))
        logger.info(localeManager.getMessage("plugin.enabled", pluginMeta.version))

        logger.info(localeManager.getMessage("plugin.caching-recipes"))
        rebuildRecipeCache()

        if (!pluginConfig.syncEnabled) {
            logger.warning(localeManager.getMessage("plugin.sync-disabled"))
        }

        logger.info(localeManager.getMessage("plugin.ready"))
        logger.info(localeManager.getMessage("plugin.decor"))
    }

    override fun onDisable() {
        logger.info(localeManager.getMessage("plugin.disabled"))
    }

    fun reloadPluginConfig() {
        backfillResources()
        reloadConfig()
        pluginConfig = PluginConfig.from(config)
        localeManager.loadLocales()
        logger.info(
            localeManager.getMessage("plugin.reloaded", pluginConfig.syncEnabled, pluginConfig.recipeBlacklist.size)
        )

        logger.info(localeManager.getMessage("plugin.recaching-recipes"))
        rebuildRecipeCache()
    }

    /** Re-reads and re-encodes the server's recipes. Cheap enough to run on a reload, not per join. */
    fun rebuildRecipeCache() {
        recipeCache.rebuild(pluginConfig.recipeBlacklist)

        logger.info(
            localeManager.getMessage("plugin.cached-recipes", recipeCache.recipeCount, recipeCache.blacklistedCount)
        )
        recipeCache.countsByType.forEach { (type, count) ->
            logger.info(localeManager.getMessage("plugin.found-recipes", count, type))
        }
        logger.info(
            localeManager.getMessage(
                "plugin.payload-sizes",
                recipeCache.fabricPayload.kilobytes,
                recipeCache.neoForgePayload.kilobytes,
                recipeCache.recipeBookEntries,
                recipeCache.recipeBookAddPackets.size,
            )
        )
        if (recipeCache.skippedSerializers.isNotEmpty()) {
            logger.warning(
                localeManager.getMessage(
                    "plugin.skipped-serializers",
                    recipeCache.skippedRecipeCount,
                    recipeCache.skippedSerializers.joinToString(", "),
                )
            )
        }
    }

    /**
     * Teaches files written by an older version about keys this one added.
     *
     * A config or language file already in the data folder is never overwritten, so without this an
     * upgraded server keeps running on the old key set — and a message the old file has no entry for
     * shows up in chat as its own raw key.
     */
    private fun backfillResources() {
        for (name in listOf("config.yml") + BUNDLED_LANG) {
            val added = ResourceBackfill.backfill(this, File(dataFolder, name), name)
            if (added.isNotEmpty()) {
                logger.info("Added ${added.size} new key(s) to $name: ${added.joinToString(", ")}")
            }
        }
    }

    private companion object {
        val BUNDLED_LANG = listOf("lang/en.yml", "lang/zh_cn.yml")
    }

    private fun registerChannels() {
        val listener = ViewerPacketListener(this).also { packetListener = it }
        val messenger = server.messenger

        for (channel in Channels.Jei.INCOMING + Channels.Rei.INCOMING) {
            messenger.registerIncomingPluginChannel(this, channel, listener)
        }
        for (channel in Channels.Jei.OUTGOING) {
            messenger.registerOutgoingPluginChannel(this, channel)
        }

        logger.info("Listening on ${Channels.Jei.INCOMING.size} JEI and ${Channels.Rei.INCOMING.size} REI channels.")
    }
}
