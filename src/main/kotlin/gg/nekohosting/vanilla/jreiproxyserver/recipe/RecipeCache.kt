package gg.nekohosting.vanilla.jreiproxyserver.recipe

import gg.nekohosting.vanilla.jreiproxyserver.nms.copyBytes
import gg.nekohosting.vanilla.jreiproxyserver.nms.minecraftServer
import gg.nekohosting.vanilla.jreiproxyserver.nms.newBuf
import gg.nekohosting.vanilla.jreiproxyserver.network.rei.ReiDisplaySyncEncoder
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
import java.util.logging.Logger

/**
 * The recipe data the plugin sends, encoded once per datapack state and reused for every player.
 *
 * Nothing here is player-specific: the payloads carry the server's numeric registry ids, which are
 * the same for every client on the server's own Minecraft version.
 */
class RecipeCache(private val logger: Logger) {

    /** One encoded loader payload. */
    class Payload(val bytes: ByteArray, val recipes: Int, val groups: Int) {
        val kilobytes: String get() = "%.1f".format(bytes.size / 1024.0)
    }

    /**
     * Everything a rebuild produces, swapped in as one value.
     *
     * On Folia a rebuild runs on the global region thread while players read this from their own
     * region threads. Publishing the whole result at once means a reader either sees the previous
     * datapack state or the new one, never a mix — the add and remove recipe-book packets in
     * particular have to belong to the same generation, or a resend duplicates entries.
     */
    class Snapshot(
        val recipeCount: Int = 0,
        val blacklistedCount: Int = 0,
        val countsByType: Map<String, Int> = emptyMap(),
        val skippedSerializers: List<String> = emptyList(),
        val skippedRecipeCount: Int = 0,
        val fabricPayload: Payload = EMPTY,
        val neoForgePayload: Payload = EMPTY,
        val reiDisplayPayload: ReiDisplaySyncEncoder.Result = EMPTY_REI,
        val recipeBookAddPackets: List<Packet<*>> = emptyList(),
        val recipeBookRemovePackets: List<Packet<*>> = emptyList(),
        val recipeBookEntries: Int = 0,
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    val recipeCount: Int get() = snapshot.recipeCount
    val blacklistedCount: Int get() = snapshot.blacklistedCount
    val countsByType: Map<String, Int> get() = snapshot.countsByType

    /** Serializers dropped from the Fabric payload because the client would refuse the whole thing. */
    val skippedSerializers: List<String> get() = snapshot.skippedSerializers
    val skippedRecipeCount: Int get() = snapshot.skippedRecipeCount

    val fabricPayload: Payload get() = snapshot.fabricPayload
    val neoForgePayload: Payload get() = snapshot.neoForgePayload
    val reiDisplayPayload: ReiDisplaySyncEncoder.Result get() = snapshot.reiDisplayPayload

    val recipeBookAddPackets: List<Packet<*>> get() = snapshot.recipeBookAddPackets
    val recipeBookRemovePackets: List<Packet<*>> get() = snapshot.recipeBookRemovePackets
    val recipeBookEntries: Int get() = snapshot.recipeBookEntries

    /**
     * Re-reads the server's recipes and re-encodes everything.
     *
     * Runs on the server's main or global region thread: it touches the recipe manager and the
     * registries. The result is published as a single snapshot when everything is encoded.
     */
    fun rebuild(blacklist: Set<String>, stripCraftingRequirements: Boolean = false) {
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

        val fabric = buildFabricPayload(kept, registries)
        val reiDisplays = ReiDisplaySyncEncoder(registries, logger).encode(kept)
        val recipeBook = buildRecipeBookPackets(kept, registries, stripCraftingRequirements)

        snapshot = Snapshot(
            recipeCount = kept.size,
            blacklistedCount = blacklisted,
            countsByType = counts.toSortedMap(),
            skippedSerializers = fabric.skippedSerializers,
            skippedRecipeCount = fabric.skippedRecipes,
            fabricPayload = fabric.payload,
            neoForgePayload = buildNeoForgePayload(kept, registries),
            reiDisplayPayload = reiDisplays,
            recipeBookAddPackets = recipeBook.add,
            recipeBookRemovePackets = recipeBook.remove,
            recipeBookEntries = recipeBook.entries,
        )
    }

    private class FabricResult(val payload: Payload, val skippedSerializers: List<String>, val skippedRecipes: Int)

    private class RecipeBookResult(val add: List<Packet<*>>, val remove: List<Packet<*>>, val entries: Int)

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
    private fun buildFabricPayload(holders: List<RecipeHolder<*>>, registries: RegistryAccess): FabricResult {
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
            return FabricResult(Payload(buf.copyBytes(), includedRecipes, included.size), skipped, skippedRecipes)
        } finally {
            buf.release()
        }
    }

    /**
     * `neoforge:recipe_content`: the set of recipe types followed by every recipe holder. NeoForge
     * decodes each holder's serializer by registry id, so unlike Fabric there is nothing to filter.
     */
    private fun buildNeoForgePayload(holders: List<RecipeHolder<*>>, registries: RegistryAccess): Payload {
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
            return Payload(buf.copyBytes(), holders.size, types.size)
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
    private fun buildRecipeBookPackets(
        holders: List<RecipeHolder<*>>,
        registries: RegistryAccess,
        stripCraftingRequirements: Boolean,
    ): RecipeBookResult {
        val recipeManager = minecraftServer.recipeManager
        val displays = ArrayList<RecipeDisplayEntry>()
        for (holder in holders) {
            recipeManager.listDisplaysForRecipe(holder.id) { display ->
                displays.add(if (stripCraftingRequirements) withoutCraftingRequirements(display) else display)
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

        return RecipeBookResult(addPackets, removePackets, displays.size)
    }

    /**
     * Drops a display's crafting requirements.
     *
     * That field is the only part of a recipe-book entry whose decoding makes the client resolve an
     * item tag, and it does so with no fallback: an unknown tag throws and the client drops the
     * connection. Dropping it avoids that, at a cost — `RecipeDisplayEntry.canCraft` returns false
     * whenever the field is absent, so every recipe reads as uncraftable and the book renders its
     * ingredients blank even when the player is holding them. Only worth trading away on a server
     * whose tags actually kick clients.
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
        private val EMPTY_REI = ReiDisplaySyncEncoder.Result(ByteArray(0), 0, 0, 0, emptySet())
    }
}
