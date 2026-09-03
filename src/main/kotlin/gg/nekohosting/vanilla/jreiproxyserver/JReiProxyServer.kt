package gg.nekohosting.vanilla.jreiproxyserver

import gg.nekohosting.vanilla.jreiproxyserver.command.CommandManager
import gg.nekohosting.vanilla.jreiproxyserver.command.PlayerResyncCommand
import gg.nekohosting.vanilla.jreiproxyserver.config.PluginConfig
import gg.nekohosting.vanilla.jreiproxyserver.config.ResourceBackfill
import gg.nekohosting.vanilla.jreiproxyserver.i18n.LocaleManager
import gg.nekohosting.vanilla.jreiproxyserver.listener.PlayerConnectionListener
import gg.nekohosting.vanilla.jreiproxyserver.network.Channels
import gg.nekohosting.vanilla.jreiproxyserver.network.ViewerPacketListener
import gg.nekohosting.vanilla.jreiproxyserver.recipe.RecipeCache
import gg.nekohosting.vanilla.jreiproxyserver.recipe.RecipeSyncService
import net.kyori.adventure.text.Component
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

        console("plugin.decor")
        console("plugin.enabled", pluginMeta.version)

        console("plugin.caching-recipes")
        rebuildRecipeCache()

        if (!pluginConfig.syncEnabled) {
            warn("plugin.sync-disabled")
        }

        console("plugin.ready")
        console("plugin.decor")
    }

    /**
     * Writes a coloured line to the console.
     *
     * The plugin logger is `java.util.logging`, which prints section signs literally; only the
     * console sender is an Adventure audience, so anything with colour has to go through it. The
     * plugin name is added by hand to match what the logger would have prefixed.
     */
    fun console(key: String, vararg args: Any) {
        server.consoleSender.sendMessage(CONSOLE_PREFIX.append(localeManager.component(key, *args)))
    }

    /** Warnings keep the logger, so they stay at WARN level; colour is stripped rather than shown raw. */
    fun warn(key: String, vararg args: Any) {
        logger.warning(localeManager.plain(key, *args))
    }

    override fun onDisable() {
        console("plugin.disabled")
    }

    fun reloadPluginConfig() {
        backfillResources()
        reloadConfig()
        pluginConfig = PluginConfig.from(config)
        localeManager.loadLocales()
        console("plugin.reloaded", pluginConfig.syncEnabled, pluginConfig.recipeBlacklist.size)

        console("plugin.recaching-recipes")
        rebuildRecipeCache()
    }

    /** Re-reads and re-encodes the server's recipes. Cheap enough to run on a reload, not per join. */
    fun rebuildRecipeCache() {
        recipeCache.rebuild(pluginConfig.recipeBlacklist, pluginConfig.stripCraftingRequirements)

        console("plugin.cached-recipes", recipeCache.recipeCount, recipeCache.blacklistedCount)
        recipeCache.countsByType.forEach { (type, count) ->
            console("plugin.found-recipes", count, type)
        }
        console(
            "plugin.payload-sizes",
            recipeCache.fabricPayload.kilobytes,
            recipeCache.neoForgePayload.kilobytes,
            recipeCache.recipeBookEntries,
            recipeCache.recipeBookAddPackets.size,
        )
        if (recipeCache.skippedSerializers.isNotEmpty()) {
            warn(
                "plugin.skipped-serializers",
                recipeCache.skippedRecipeCount,
                recipeCache.skippedSerializers.joinToString(", "),
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
        val CONSOLE_PREFIX: Component = Component.text("[JReiProxyServer] ")
    }

    private fun registerChannels() {
        val listener = ViewerPacketListener(this).also { packetListener = it }
        val messenger = server.messenger

        // REI's cheat channels are only advertised when asked for: their presence makes REI throw
        // away the recipes this plugin exists to deliver. Transfer is gated separately by REI and
        // is always safe.
        val reiChannels = Channels.Rei.SAFE_INCOMING +
            if (pluginConfig.reiCheatChannels) Channels.Rei.CHEAT_TRIO_INCOMING else emptyList()

        for (channel in Channels.Jei.INCOMING + reiChannels) {
            messenger.registerIncomingPluginChannel(this, channel, listener)
        }
        for (channel in Channels.Jei.OUTGOING) {
            messenger.registerOutgoingPluginChannel(this, channel)
        }

        logger.info("Listening on ${Channels.Jei.INCOMING.size} JEI and ${reiChannels.size} REI channels.")
    }
}
