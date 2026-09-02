package gg.nekohosting.vanilla.jreiproxyserver.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.InputStreamReader

/**
 * Adds keys a plugin update introduced into the copies already sitting in the data folder.
 *
 * Without this, a config or language file written by an older version silently lacks every new key,
 * and a missing message surfaces to players as its own raw key.
 */
object ResourceBackfill {

    /**
     * Copies any key present in the bundled `resourcePath` but missing from `file`, keeping the
     * values and comments already there. Returns how many keys were added; the file is only
     * rewritten when that is non-zero.
     */
    fun backfill(plugin: Plugin, file: File, resourcePath: String): List<String> {
        val stream = plugin.getResource(resourcePath) ?: return emptyList()
        if (!file.exists()) return emptyList()

        val bundled = InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
        val existing = YamlConfiguration.loadConfiguration(file)

        val added = ArrayList<String>()
        for (key in bundled.getKeys(true)) {
            // Sections are created implicitly by setting the leaf keys under them.
            if (bundled.isConfigurationSection(key) || existing.contains(key)) continue
            existing.set(key, bundled.get(key))
            existing.setComments(key, bundled.getComments(key))
            added.add(key)
        }

        if (added.isNotEmpty()) {
            existing.save(file)
        }
        return added
    }
}
