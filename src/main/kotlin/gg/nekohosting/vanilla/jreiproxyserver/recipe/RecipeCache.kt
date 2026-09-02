package gg.nekohosting.vanilla.jreiproxyserver.recipe

import gg.nekohosting.vanilla.jreiproxyserver.nms.copyBytes
import gg.nekohosting.vanilla.jreiproxyserver.nms.minecraftServer
import gg.nekohosting.vanilla.jreiproxyserver.nms.newBuf
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry
import java.util.Optional

/**
 * The recipe data the plugin sends, encoded once per datapack state and reused for every player.
 *
 * Nothing here is player-specific: the payloads carry the server's numeric registry ids, which are
 * the same for every client on the server's own Minecraft version.
 */
class RecipeCache {

    /** One encoded loader payload. */
    class Payload(val bytes: ByteArray, val recipes: Int, val groups: Int) {
        val kilobytes: String get() = "%.1f".format(bytes.size / 1024.0)
    }

    var recipeCount: Int = 0
        private set
    var blacklistedCount: Int = 0
        private set
    var countsByType: Map<String, Int> = emptyMap()
        private set

    /** Serializers dropped from the Fabric payload because the client would refuse the whole thing. */
    var skippedSerializers: List<String> = emptyList()
        private set
    var skippedRecipeCount: Int = 0
        private set

    var fabricPayload: Payload = EMPTY
        private set
    var neoForgePayload: Payload = EMPTY
        private set

    var recipeBookAddPackets: List<Packet<*>> = emptyList()
        private set
    var recipeBookRemovePackets: List<Packet<*>> = emptyList()
        private set
    var recipeBookEntries: Int = 0
        private set

    /**
     * Re-reads the server's recipes and re-encodes everything.
     *
     * Runs on the main thread: it touches the recipe manager and the registries.
     */
    fun rebuild(blacklist: Set<String>) {
        val server = minecraftServer
        val recipeManager = server.recipeManager
        val registries = server.registryAccess()

        val kept = ArrayList<RecipeHolder<*>>()
        var blacklisted = 0
        val counts = LinkedHashMap<String, Int>()

        for (holder in recipeManager.getRecipes()) {
            if (holder.id.identifier().toString() in blacklist) {
                blacklisted++
                continue
            }
            kept.add(holder)
            val type = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().type)?.path ?: "unknown"
            counts[type] = (counts[type] ?: 0) + 1
        }

        recipeCount = kept.size
        blacklistedCount = blacklisted
        countsByType = counts.toSortedMap()

        buildFabricPayload(kept, registries)
        buildNeoForgePayload(kept, registries)
        buildRecipeBookPackets(kept, registries)
    }

    /**
     * `fabric:recipe_sync`: a list of (serializer id, recipes) groups, each recipe written by its
     * own serializer's stream codec.
     *
     * Fabric's decoder throws away the *entire* payload the moment it meets a serializer the client
     * did not opt into, and that opt-in list arrives during the configuration phase, which a plugin
     * cannot see — Bukkit only exposes play-phase plugin messages. Recipe viewers opt into the
     * vanilla serializers, so anything outside the `minecraft` namespace is dropped here: losing one
     * modded group costs those recipes, keeping it costs every recipe on the server.
     */
    private fun buildFabricPayload(holders: List<RecipeHolder<*>>, registries: RegistryAccess) {
        val bySerializer = LinkedHashMap<RecipeSerializer<*>, MutableList<RecipeHolder<*>>>()
        for (holder in holders) {
            bySerializer.getOrPut(holder.value().serializer) { ArrayList() }.add(holder)
        }

        val included = LinkedHashMap<RecipeSerializer<*>, MutableList<RecipeHolder<*>>>()
        val skipped = ArrayList<String>()
        var skippedRecipes = 0
        var includedRecipes = 0

        for ((serializer, group) in bySerializer) {
            val id = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer)
            if (id != null && id.namespace == "minecraft") {
                included[serializer] = group
                includedRecipes += group.size
            } else {
                skipped.add(id?.toString() ?: serializer.toString())
                skippedRecipes += group.size
            }
        }

        skippedSerializers = skipped
        skippedRecipeCount = skippedRecipes

        val buf = registries.newBuf()
        try {
            buf.writeVarInt(included.size)
            for ((serializer, group) in included) {
                buf.writeIdentifier(BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer)!!)
                buf.writeVarInt(group.size)
                // Deprecated upstream but still the only way to reach a serializer's wire format;
                // Fabric's own recipe sync encodes through exactly this call.
                @Suppress("UNCHECKED_CAST", "DEPRECATION")
                val codec = serializer.streamCodec() as StreamCodec<RegistryFriendlyByteBuf, Recipe<*>>
                for (holder in group) {
                    buf.writeResourceKey(holder.id)
                    codec.encode(buf, holder.value())
                }
            }
            fabricPayload = Payload(buf.copyBytes(), includedRecipes, included.size)
        } finally {
            buf.release()
        }
    }

    /**
     * `neoforge:recipe_content`: the set of recipe types followed by every recipe holder. NeoForge
     * decodes each holder's serializer by registry id, so unlike Fabric there is nothing to filter.
     */
    private fun buildNeoForgePayload(holders: List<RecipeHolder<*>>, registries: RegistryAccess) {
        val types = LinkedHashSet<RecipeType<*>>()
        for (holder in holders) {
            types.add(holder.value().type)
        }

        val buf = registries.newBuf()
        try {
            buf.writeVarInt(types.size)
            for (type in types) {
                buf.writeVarInt(BuiltInRegistries.RECIPE_TYPE.getId(type))
            }
            buf.writeVarInt(holders.size)
            for (holder in holders) {
                RecipeHolder.STREAM_CODEC.encode(buf, holder)
            }
            neoForgePayload = Payload(buf.copyBytes(), holders.size, types.size)
        } finally {
            buf.release()
        }
    }

    /**
     * The vanilla recipe book, carrying every recipe on the server.
     *
     * REI ignores the loader sync channels and builds its list from the recipe book, which normally
     * holds only what a player has unlocked. Sending the lot is what makes REI see the server's
     * recipes. Nothing is unlocked server-side by this: the server still checks what a player
     * actually knows when they click a recipe.
     */
    private fun buildRecipeBookPackets(holders: List<RecipeHolder<*>>, registries: RegistryAccess) {
        val recipeManager = minecraftServer.recipeManager
        val displays = ArrayList<RecipeDisplayEntry>()
        for (holder in holders) {
            recipeManager.listDisplaysForRecipe(holder.id) { display ->
                displays.add(withoutCraftingRequirements(display))
            }
        }

        val addPackets = ArrayList<Packet<*>>()
        var batch = ArrayList<ClientboundRecipeBookAddPacket.Entry>()

        // Entry sizes vary enough that a fixed count per packet would either waste packets or
        // overshoot the frame limit on a heavy datapack, so measure the encoded size as we go.
        val buf = registries.newBuf()
        try {
            var batchStart = 0
            for (display in displays) {
                // (notification, highlight) both false: no toast, no "new recipe" glow.
                val entry = ClientboundRecipeBookAddPacket.Entry(display, false, false)
                ClientboundRecipeBookAddPacket.Entry.STREAM_CODEC.encode(buf, entry)
                batch.add(entry)
                val size = buf.readableBytes()
                if (size - batchStart >= RECIPE_BOOK_BATCH_BYTES) {
                    addPackets.add(ClientboundRecipeBookAddPacket(batch.toList(), false))
                    batch = ArrayList()
                    batchStart = size
                }
            }
        } finally {
            buf.release()
        }
        if (batch.isNotEmpty()) {
            // replace=false: add to what the server already sent, never wipe the player's book.
            addPackets.add(ClientboundRecipeBookAddPacket(batch.toList(), false))
        }

        // REI keeps no identity of its own per display and does not deduplicate, so a second send
        // would show every recipe twice. Removing our own entries first makes any number of sends
        // leave exactly one copy.
        val ids = displays.map { it.id }
        val removePackets = ArrayList<Packet<*>>()
        val perBatch = maxOf(1, ids.size / maxOf(1, addPackets.size))
        var from = 0
        while (from < ids.size) {
            val to = minOf(from + perBatch, ids.size)
            removePackets.add(ClientboundRecipeBookRemovePacket(ids.subList(from, to).toList()))
            from = to
        }

        recipeBookAddPackets = addPackets
        recipeBookRemovePackets = removePackets
        recipeBookEntries = displays.size
    }

    /**
     * Drops a display's crafting requirements.
     *
     * That field is the only part of a recipe-book entry whose decoding makes the client resolve an
     * item tag, and it does so with no fallback: an unknown tag throws and the client drops the
     * connection. Vanilla never trips over it because it only ever sends the handful of recipes a
     * player has unlocked. Nothing is lost here — the field only drives the vanilla book's "can I
     * craft this" shading, and recipe viewers read the display itself.
     */
    private fun withoutCraftingRequirements(display: RecipeDisplayEntry): RecipeDisplayEntry =
        RecipeDisplayEntry(display.id, display.display, display.group, display.category, Optional.empty())

    /** The packet that makes an already-loaded JEI or REI re-read its recipes. */
    fun buildRecipeUpdatePacket(): Packet<*> {
        val recipeManager = minecraftServer.recipeManager
        return ClientboundUpdateRecipesPacket(
            recipeManager.synchronizedItemProperties,
            recipeManager.synchronizedStonecutterRecipes,
        )
    }

    companion object {
        /** Keeps any single recipe-book packet clear of the network frame limit. */
        private const val RECIPE_BOOK_BATCH_BYTES = 512 * 1024

        private val EMPTY = Payload(ByteArray(0), 0, 0)
    }
}
