package gg.nekohosting.vanilla.jreiproxyserver.network.rei

import gg.nekohosting.vanilla.jreiproxyserver.nms.copyBytes
import gg.nekohosting.vanilla.jreiproxyserver.nms.newBuf
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.resources.Identifier
import net.minecraft.util.context.ContextMap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.BlastingRecipe
import net.minecraft.world.item.crafting.CampfireCookingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.SmeltingRecipe
import net.minecraft.world.item.crafting.SmokingRecipe
import net.minecraft.world.item.crafting.SmithingTrimRecipe
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay
import net.minecraft.world.item.crafting.display.RecipeDisplay
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay
import net.minecraft.world.item.crafting.display.SlotDisplay
import net.minecraft.world.item.crafting.display.SlotDisplayContext
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay
import net.minecraft.world.item.equipment.trim.TrimPattern
import java.util.logging.Logger

/**
 * Encodes the server's recipes as REI 1.21.11 `sync_displays`.
 *
 * This is deliberately independent of REI classes: a Paper plugin cannot load classes from a
 * client mod. The field order and serializer ids mirror REI 21.11's registered display codecs.
 * Unlike the vanilla recipe book, REI continues to consume this packet when its three cheat
 * channels make `canUsePackets()` true.
 */
class ReiDisplaySyncEncoder(
    private val registries: RegistryAccess,
    private val logger: Logger,
) {

    data class Result(
        val bytes: ByteArray,
        val displays: Int,
        val serializers: Int,
        val skippedDisplays: Int,
        val skippedTypes: Set<String>,
    ) {
        val kilobytes: String get() = "%.1f".format(bytes.size / 1024.0)
    }

    private val slotContext = ContextMap.Builder()
        .withParameter(SlotDisplayContext.REGISTRIES, registries)
        .create(SlotDisplayContext.CONTEXT)

    fun encode(holders: List<RecipeHolder<*>>): Result {
        val displays = ArrayList<WireDisplay>()
        val skippedTypes = LinkedHashSet<String>()
        var skipped = 0

        for (holder in holders) {
            for (display in holder.value().display()) {
                val converted = try {
                    convert(holder, display)
                } catch (e: Exception) {
                    logger.warning(
                        "Failed to convert ${holder.id().identifier()} to an REI display: " +
                            "${e.javaClass.simpleName}: ${e.message ?: ""}"
                    )
                    emptyList()
                }

                if (converted.isEmpty()) {
                    skipped++
                    skippedTypes.add(display.javaClass.simpleName)
                } else {
                    displays.addAll(converted)
                }
            }
        }

        val encoded = ArrayList<Pair<String, ByteArray>>(displays.size)
        for (display in displays) {
            val content = registries.newBuf()
            try {
                content.writeIdentifier(Identifier.parse(display.serializerId))
                display.encode(content)
                encoded.add(display.serializerId to content.copyBytes())
            } catch (e: Exception) {
                skipped++
                skippedTypes.add(display.serializerId)
                logger.warning(
                    "Failed to encode REI display ${display.serializerId}: " +
                        "${e.javaClass.simpleName}: ${e.message ?: ""}"
                )
            } finally {
                content.release()
            }
        }

        val packet = registries.newBuf()
        try {
            // DisplaySyncPacket.SyncType.SET is ordinal 1 and REI uses ByteBufCodecs.idMapper.
            packet.writeVarInt(SYNC_TYPE_SET)
            packet.writeVarInt(encoded.size)
            for ((_, content) in encoded) {
                // REI isolates each display behind a success flag and length-delimited byte array.
                packet.writeBoolean(true)
                packet.writeVarInt(content.size)
                packet.writeBytes(content)
            }
            // Used by REI's server-side incremental tracker, but ignored by the client handler.
            packet.writeLong(DISPLAY_VERSION)

            return Result(
                bytes = packet.copyBytes(),
                displays = encoded.size,
                serializers = encoded.mapTo(LinkedHashSet()) { it.first }.size,
                skippedDisplays = skipped,
                skippedTypes = skippedTypes,
            )
        } finally {
            packet.release()
        }
    }

    private fun convert(holder: RecipeHolder<*>, display: RecipeDisplay): List<WireDisplay> {
        val location = holder.id().identifier()
        return when (display) {
            is ShapedCraftingRecipeDisplay -> listOf(
                BasicDisplay(
                    serializerId = SHAPED,
                    inputs = display.ingredients().map(::resolve),
                    outputs = listOf(resolve(display.result())),
                    location = location,
                    suffix = { buf ->
                        buf.writeInt(display.width())
                        buf.writeInt(display.height())
                    },
                )
            )

            is ShapelessCraftingRecipeDisplay -> listOf(
                BasicDisplay(
                    serializerId = SHAPELESS,
                    inputs = display.ingredients().map(::resolve),
                    outputs = listOf(resolve(display.result())),
                    location = location,
                )
            )

            is FurnaceRecipeDisplay -> cookingDisplays(holder, display, location)

            is StonecutterRecipeDisplay -> listOf(
                BasicDisplay(
                    serializerId = STONE_CUTTING,
                    inputs = listOf(resolve(display.input())),
                    outputs = listOf(resolve(display.result())),
                    location = location,
                )
            )

            is SmithingRecipeDisplay -> smithingDisplays(holder, display, location)
            else -> emptyList()
        }
    }

    private fun cookingDisplays(
        holder: RecipeHolder<*>,
        display: FurnaceRecipeDisplay,
        location: Identifier,
    ): List<WireDisplay> {
        val input = listOf(resolve(display.ingredient()))
        val output = listOf(resolve(display.result()))
        val serializer = when (holder.value()) {
            is SmeltingRecipe -> SMELTING
            is SmokingRecipe -> SMOKING
            is BlastingRecipe -> BLASTING
            is CampfireCookingRecipe -> CAMPFIRE
            else -> return emptyList()
        }

        return listOf(
            BasicDisplay(
                serializerId = serializer,
                inputs = input,
                outputs = output,
                location = location,
                suffix = { buf ->
                    if (serializer == CAMPFIRE) {
                        buf.writeDouble(display.duration().toDouble())
                    } else {
                        buf.writeFloat(display.experience())
                        buf.writeDouble(display.duration().toDouble())
                    }
                },
            )
        )
    }

    private fun smithingDisplays(
        holder: RecipeHolder<*>,
        display: SmithingRecipeDisplay,
        location: Identifier,
    ): List<WireDisplay> {
        val template = resolve(display.template())
        val base = resolve(display.base())
        val addition = resolve(display.addition())

        if (holder.value() is SmithingTrimRecipe) {
            val trimResult = display.result() as? SlotDisplay.SmithingTrimDemoSlotDisplay
                ?: return emptyList()

            // REI emits one trim display per material so its three independently-cycling input
            // slots never combine the output with a different trim material.
            return addition.map { material ->
                SmithingDisplay(
                    serializerId = SMITHING_TRIMMING,
                    inputs = listOf(template, base, listOf(material.copyWithCount(1))),
                    outputs = listOf(base),
                    location = location,
                    type = SMITHING_TRIM,
                    pattern = trimResult.pattern(),
                )
            }
        }

        return listOf(
            SmithingDisplay(
                serializerId = SMITHING,
                inputs = listOf(template, base, addition),
                outputs = listOf(resolve(display.result())),
                location = location,
                type = SMITHING_TRANSFORM,
            )
        )
    }

    /** REI's EntryIngredients.ofSlotDisplay, with tags expanded to concrete item stacks. */
    private fun resolve(display: SlotDisplay): List<ItemStack> = when (display) {
        is SlotDisplay.Empty, is SlotDisplay.AnyFuel -> emptyList()
        else -> display.resolveForStacks(slotContext)
            .asSequence()
            .filterNot(ItemStack::isEmpty)
            .map(ItemStack::copy)
            .toList()
    }

    private sealed interface WireDisplay {
        val serializerId: String
        fun encode(buf: RegistryFriendlyByteBuf)
    }

    private data class BasicDisplay(
        override val serializerId: String,
        val inputs: List<List<ItemStack>>,
        val outputs: List<List<ItemStack>>,
        val location: Identifier,
        val suffix: (RegistryFriendlyByteBuf) -> Unit = {},
    ) : WireDisplay {
        override fun encode(buf: RegistryFriendlyByteBuf) {
            writeReiIngredients(buf, inputs)
            writeReiIngredients(buf, outputs)
            writeOptionalIdentifier(buf, location)
            suffix(buf)
        }
    }

    private data class SmithingDisplay(
        override val serializerId: String,
        val inputs: List<List<ItemStack>>,
        val outputs: List<List<ItemStack>>,
        val location: Identifier,
        val type: Int,
        val pattern: Holder<TrimPattern>? = null,
    ) : WireDisplay {
        override fun encode(buf: RegistryFriendlyByteBuf) {
            writeReiIngredients(buf, inputs)
            writeReiIngredients(buf, outputs)
            buf.writeBoolean(true)
            buf.writeVarInt(type)
            writeOptionalIdentifier(buf, location)
            pattern?.let { TrimPattern.STREAM_CODEC.encode(buf, it) }
        }
    }

    private companion object {
        const val SYNC_TYPE_SET = 1
        const val DISPLAY_VERSION = 1L

        const val SHAPED = "minecraft:default/crafting/shaped"
        const val SHAPELESS = "minecraft:default/crafting/shapeless"
        const val SMELTING = "minecraft:default/smelting"
        const val SMOKING = "minecraft:default/smoking"
        const val BLASTING = "minecraft:default/blasting"
        const val CAMPFIRE = "minecraft:default/campfire"
        const val STONE_CUTTING = "minecraft:default/stone_cutting"
        const val SMITHING = "minecraft:default/smithing"
        const val SMITHING_TRIMMING = "minecraft:default/smithing/trimming"

        const val SMITHING_TRIM = 0
        const val SMITHING_TRANSFORM = 1
    }
}

private val REI_ITEM_ENTRY_TYPE: Identifier = Identifier.withDefaultNamespace("item")

private fun writeReiIngredients(buf: RegistryFriendlyByteBuf, ingredients: List<List<ItemStack>>) {
    buf.writeVarInt(ingredients.size)
    for (ingredient in ingredients) {
        buf.writeVarInt(ingredient.size)
        for (stack in ingredient) {
            // EntryStack's discriminator precedes the value codec.
            buf.writeIdentifier(REI_ITEM_ENTRY_TYPE)
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack)
        }
    }
}

private fun writeOptionalIdentifier(buf: RegistryFriendlyByteBuf, identifier: Identifier) {
    buf.writeBoolean(true)
    Identifier.STREAM_CODEC.encode(buf, identifier)
}
