package gg.nekohosting.vanilla.jreiproxyserver.i18n

import gg.nekohosting.vanilla.jreiproxyserver.JReiProxyServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
     * The message with its `&` colour codes still in place.
     *
     * The lookup goes to the admin's file first, then to the jar. The fallback deliberately reads
     * the bundled copy rather than the English file on disk: that one can be just as out of date as
     * the language file, which is how a raw key ends up in chat.
     */
    private fun raw(key: String, vararg args: Any): String {
        val message = messages?.getString(key)
            ?: bundled?.getString(key)
            ?: bundledEnglish?.getString(key)
            ?: return key
        return MessageFormat.format(message, *args)
    }

    /**
     * The message as a component, which is the only form that renders in colour.
     *
     * Everything the plugin shows a player or writes to the console goes through here. Handing a
     * legacy-coded string to `java.util.logging` instead prints the section signs literally,
     * because the plugin logger never passes through Adventure's serialiser.
     */
    fun component(key: String, vararg args: Any): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(raw(key, *args))

    /** The message with all formatting removed, for log levels that carry no colour. */
    fun plain(key: String, vararg args: Any): String =
        PlainTextComponentSerializer.plainText().serialize(component(key, *args))
}
