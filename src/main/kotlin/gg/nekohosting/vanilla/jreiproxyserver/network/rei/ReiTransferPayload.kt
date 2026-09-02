package gg.nekohosting.vanilla.jreiproxyserver.network.rei

import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/** One recipe slot: where it sits in the display, and every stack that would satisfy it. */
data class ReiInput(val index: Int, val stacks: List<ItemStack>)

/**
 * A slot the transfer may read from or write to.
 *
 * REI addresses slots two ways — by index into the open container, or by index into the player's
 * inventory — and says which in the payload. The distinction matters: a container slot enforces its
 * own placement rules, an inventory slot does not.
 */
sealed interface ReiSlot {
    fun get(player: ServerPlayer): ItemStack
    fun set(player: ServerPlayer, stack: ItemStack)
    fun take(player: ServerPlayer, amount: Int): ItemStack
    fun allowModification(player: ServerPlayer): Boolean
    fun canPlace(player: ServerPlayer, stack: ItemStack): Boolean

    /** A slot of the currently open container. */
    data class Menu(val index: Int) : ReiSlot {
        override fun get(player: ServerPlayer): ItemStack = player.containerMenu.getSlot(index).item
        override fun set(player: ServerPlayer, stack: ItemStack) = player.containerMenu.getSlot(index).set(stack)
        override fun take(player: ServerPlayer, amount: Int): ItemStack =
            player.containerMenu.getSlot(index).remove(amount)

        override fun allowModification(player: ServerPlayer): Boolean =
            player.containerMenu.getSlot(index).allowModification(player)

        override fun canPlace(player: ServerPlayer, stack: ItemStack): Boolean =
            player.containerMenu.getSlot(index).mayPlace(stack)
    }

    /** A slot of the player's own inventory. */
    data class PlayerInventory(val index: Int) : ReiSlot {
        override fun get(player: ServerPlayer): ItemStack = player.inventory.getItem(index)
        override fun set(player: ServerPlayer, stack: ItemStack) = player.inventory.setItem(index, stack)
        override fun take(player: ServerPlayer, amount: Int): ItemStack = player.inventory.removeItem(index, amount)
        override fun allowModification(player: ServerPlayer): Boolean = true
        override fun canPlace(player: ServerPlayer, stack: ItemStack): Boolean = true
    }
}

/** The decoded `roughlyenoughitems:move_items_new` body. */
data class ReiTransferRequest(
    val inputs: List<ReiInput>,
    val inputSlots: List<ReiSlot>,
    val inventorySlots: List<ReiSlot>,
)

/**
 * Reads the NBT blob REI sends with a transfer request.
 *
 * The shape is REI's own, not vanilla's: ingredients are `EntryStack`s, which serialise as a
 * `{type, value}` pair dispatched on an entry-type id. Only `minecraft:item` is meaningful here —
 * a fluid cannot go into an item slot — so other entry types are dropped from the ingredient.
 */
object ReiTransferPayload {

    /** REI's protocol version. A mismatch means the client is speaking a shape we do not know. */
    private const val SUPPORTED_VERSION = 1

    private const val ITEM_ENTRY_TYPE = "minecraft:item"
    private const val VANILLA_SLOT_ID = "roughlyenoughitems:vanilla"
    private const val PLAYER_SLOT_ID = "roughlyenoughitems:player"

    fun decode(data: CompoundTag, registries: RegistryAccess): ReiTransferRequest {
        val version = data.getInt("Version").orElse(-1)
        check(version == SUPPORTED_VERSION) {
            "Server and client REI protocol version mismatch! Server: $SUPPORTED_VERSION, Client: $version"
        }

        val ops = registries.createSerializationContext(NbtOps.INSTANCE)

        val inputs = data.getListOrEmpty("Inputs").mapNotNull { tag ->
            val compound = tag as? CompoundTag ?: return@mapNotNull null
            val index = compound.getInt("Index").orElse(-1)
            if (index < 0) return@mapNotNull null

            val stacks = compound.getListOrEmpty("Ingredient").mapNotNull { entry ->
                val entryTag = entry as? CompoundTag ?: return@mapNotNull null
                if (entryTag.getString("type").orElse("") != ITEM_ENTRY_TYPE) return@mapNotNull null
                val value = entryTag.get("value") ?: return@mapNotNull null
                ItemStack.CODEC.parse(ops, value).result().orElse(null)
            }
            ReiInput(index, stacks)
        }

        return ReiTransferRequest(
            inputs = inputs,
            inputSlots = data.getListOrEmpty("InputSlots").mapNotNull(::readSlot),
            inventorySlots = data.getListOrEmpty("InventorySlots").mapNotNull(::readSlot),
        )
    }

    private fun readSlot(tag: net.minecraft.nbt.Tag): ReiSlot? {
        val compound = tag as? CompoundTag ?: return null
        val slot = compound.getInt("Slot").orElse(-1)
        if (slot < 0) return null
        return when (compound.getString("id").orElse("")) {
            VANILLA_SLOT_ID -> ReiSlot.Menu(slot)
            PLAYER_SLOT_ID -> ReiSlot.PlayerInventory(slot)
            else -> null
        }
    }
}
