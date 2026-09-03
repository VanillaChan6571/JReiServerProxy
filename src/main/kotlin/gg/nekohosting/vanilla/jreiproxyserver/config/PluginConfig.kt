package gg.nekohosting.vanilla.jreiproxyserver.config

import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale

/** Who is sent the full vanilla recipe book. */
enum class RecipeBookMode {
    /** Only clients that registered a REI channel. */
    AUTO,

    /** Every client carrying a recipe viewer, detected or not. */
    ALL,

    /** Nobody. */
    OFF;

    companion object {
        fun parse(raw: String?): RecipeBookMode =
            entries.firstOrNull { it.name == raw?.trim()?.uppercase(Locale.ROOT) } ?: AUTO
    }
}

/** An immutable snapshot of `config.yml`, re-read on `/jeiproxy reload`. */
data class PluginConfig(
    val language: String,
    val syncEnabled: Boolean,
    val syncOnJoin: Boolean,
    val syncOnDatapackReload: Boolean,
    val recipeBookMode: RecipeBookMode,
    val triggerRecipeUpdate: Boolean,
    val notifyPlayer: Boolean,
    val stripCraftingRequirements: Boolean,
    val playerResyncCooldownSeconds: Int,
    val cheatEnabled: Boolean,
    val cheatAllowCreative: Boolean,
    val reiCheatChannels: Boolean,
    val recipeTransferEnabled: Boolean,
    val recipeBlacklist: Set<String>,
    val debug: Boolean,
) {
    companion object {
        fun from(config: FileConfiguration): PluginConfig = PluginConfig(
            language = config.getString("language", "en")!!,
            syncEnabled = config.getBoolean("recipe-sync.enabled", true),
            syncOnJoin = config.getBoolean("recipe-sync.on-join", true),
            syncOnDatapackReload = config.getBoolean("recipe-sync.on-datapack-reload", true),
            recipeBookMode = RecipeBookMode.parse(config.getString("recipe-sync.recipe-book")),
            triggerRecipeUpdate = config.getBoolean("recipe-sync.trigger-recipe-update", true),
            notifyPlayer = config.getBoolean("recipe-sync.notify-player", true),
            stripCraftingRequirements = config.getBoolean("recipe-sync.strip-crafting-requirements", false),
            playerResyncCooldownSeconds = config.getInt("recipe-sync.player-resync-cooldown-seconds", 30),
            cheatEnabled = config.getBoolean("cheat-mode.enabled", true),
            cheatAllowCreative = config.getBoolean("cheat-mode.allow-creative", false),
            reiCheatChannels = config.getBoolean("cheat-mode.rei-channels", true),
            recipeTransferEnabled = config.getBoolean("recipe-transfer.enabled", true),
            recipeBlacklist = config.getStringList("recipe-blacklist").toSet(),
            debug = config.getBoolean("debug", false),
        )
    }
}
