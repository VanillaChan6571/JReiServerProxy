package com.xinian.jreiproxyserver.i18n

import com.xinian.jreiproxyserver.JReiProxyServer
import org.bukkit.ChatColor
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.text.MessageFormat

class LocaleManager(private val plugin: JReiProxyServer) {

    /** The admin's own file, which may be from an older version and missing newer keys. */
    private var messages: FileConfiguration? = null

    /** The same language as shipped in the jar. Always complete for this build. */
    private var bundled: FileConfiguration? = null

    /** English as shipped in the jar — the last resort, and the only file guaranteed to exist. */
    private var bundledEnglish: FileConfiguration? = null

    init {
        loadLocales()
    }

    fun loadLocales() {
        val lang = plugin.config.getString("language", "en")!!

        val langFile = File(plugin.dataFolder, "lang/$lang.yml")
        if (!langFile.exists() && plugin.getResource("lang/$lang.yml") != null) {
            plugin.saveResource("lang/$lang.yml", false)
        }

        messages = if (langFile.exists()) YamlConfiguration.loadConfiguration(langFile) else null
        bundled = loadBundled("lang/$lang.yml")
        bundledEnglish = loadBundled("lang/en.yml")
    }

    private fun loadBundled(resourcePath: String): FileConfiguration? {
        val stream = plugin.getResource(resourcePath) ?: return null
        return InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
    }

    /**
     * Looks the key up in the admin's file first, then in the jar.
     *
     * The fallback deliberately reads the bundled copy rather than the English file on disk: that
     * one can be just as out of date as the language file, which is how a raw key ends up in chat.
     */
    fun getMessage(key: String, vararg args: Any): String {
        val message = messages?.getString(key)
            ?: bundled?.getString(key)
            ?: bundledEnglish?.getString(key)
            ?: return key

        return ChatColor.translateAlternateColorCodes('&', MessageFormat.format(message, *args))
    }
}
