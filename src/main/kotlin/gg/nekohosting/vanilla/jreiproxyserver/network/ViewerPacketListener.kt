package gg.nekohosting.vanilla.jreiproxyserver.network

import gg.nekohosting.vanilla.jreiproxyserver.JReiProxyServer
import gg.nekohosting.vanilla.jreiproxyserver.nms.copyBytes
import gg.nekohosting.vanilla.jreiproxyserver.nms.minecraftServer
import gg.nekohosting.vanilla.jreiproxyserver.nms.newBuf
import gg.nekohosting.vanilla.jreiproxyserver.nms.nms
import gg.nekohosting.vanilla.jreiproxyserver.network.rei.NotEnoughMaterialsException
import gg.nekohosting.vanilla.jreiproxyserver.network.rei.ReiTransfer
import gg.nekohosting.vanilla.jreiproxyserver.network.rei.ReiTransferPayload
import gg.nekohosting.vanilla.jreiproxyserver.network.rei.SplitPacketAssembler
import gg.nekohosting.vanilla.jreiproxyserver.nms.readBuf
import net.minecraft.ChatFormatting
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.resources.Identifier
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener

/**
 * Handles the packets JEI and REI clients send.
 *
 * Both mods moved to Minecraft's `CustomPacketPayload` model, so every packet arrives on its own
 * channel with no packet id and no handshake — the channel name is the whole routing decision.
 * The payload layouts here mirror the mods' own stream codecs.
 */
class ViewerPacketListener(private val plugin: JReiProxyServer) : PluginMessageListener {

    private val transfer = RecipeTransfer(plugin.logger)
    private val reiTransfer = ReiTransfer { if (plugin.pluginConfig.debug) plugin.logger.info("[rei] $it") }
    private val reiFrames = SplitPacketAssembler()

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        // Plugin messages are dispatched on the main thread, so the container can be touched here.
        try {
            when (channel) {
                Channels.Jei.REQUEST_CHEAT_PERMISSION -> sendCheatPermission(player)
                Channels.Jei.DELETE_PLAYER_ITEM -> onDeleteItem(player, message, optional = false)
                Channels.Jei.GIVE_ITEM_STACK -> onJeiGiveItem(player, message)
                Channels.Jei.SET_HOTBAR_ITEM_STACK -> onSetHotbarItem(player, message, optional = false)
                Channels.Jei.RECIPE_TRANSFER -> onRecipeTransfer(player, message, counted = false)
                Channels.Jei.RECIPE_TRANSFER_COUNTED -> onRecipeTransfer(player, message, counted = true)

                in REI_CHANNELS -> {
                    // Every REI packet is wrapped in Architectury's split-packet framing, so the
                    // body only becomes readable once that is stripped — and a fragment yields
                    // nothing until the rest of it arrives.
                    if (plugin.pluginConfig.debug) {
                        val head = message.take(3).joinToString(" ") { "0x%02x".format(it) }
                        plugin.logger.info("[rei] $channel from ${player.name}: ${message.size} bytes, head=[$head]")
                    }
                    val body = reiFrames.accept(player.uniqueId, channel, message)
                    if (body == null) {
                        if (plugin.pluginConfig.debug) {
                            plugin.logger.info("[rei] $channel: fragment held, waiting for the rest")
                        }
                        return
                    }
                    when (channel) {
                        Channels.Rei.DELETE_ITEM -> onReiDeleteItem(player)
                        Channels.Rei.CREATE_ITEM -> onReiCreateItem(player, body, toCursor = false)
                        Channels.Rei.CREATE_ITEM_GRAB -> onReiCreateItem(player, body, toCursor = true)
                        Channels.Rei.CREATE_ITEM_HOTBAR -> onSetHotbarItem(player, body, optional = true)
                        Channels.Rei.MOVE_ITEMS -> onReiRecipeTransfer(player, body)
                    }
                }
            }
        } catch (e: Exception) {
            // A decode failure puts whatever it managed to read into the exception message, which
            // for a recipe transfer is the entire payload — kilobytes of NBT on one console line.
            // The first bytes are what actually identifies a framing problem, so log those instead.
            val head = message.take(4).joinToString(" ") { "0x%02x".format(it) }
            val reason = e.message?.replace(CONTROL_CHARACTERS, "")?.take(120) ?: ""
            plugin.logger.warning(
                "Failed to handle $channel from ${player.name} " +
                    "(${message.size} bytes, head=[$head]): ${e.javaClass.simpleName}: $reason"
            )
            if (plugin.pluginConfig.debug) e.printStackTrace()
        }
    }

    // ---------------------------------------------------------------- cheat mode

    /**
     * Whether the player may pull items out of the recipe viewer.
     *
     * The permission node is the gate. `allow-creative` is the one extra door JEI's own server
     * config offers, for servers that want any creative-mode player to be able to cheat.
     */
    fun hasCheatPermission(player: Player): Boolean {
        val config = plugin.pluginConfig
        if (!config.cheatEnabled) return false
        if (player.hasPermission(CHEAT_PERMISSION)) return true
        return config.cheatAllowCreative && player.gameMode == GameMode.CREATIVE
    }

    /**
     * Answers `jei:request_cheat_permission`.
     *
     * The string list is what JEI quotes back to the player when it refuses a cheat, so it has to
     * describe the doors this server actually leaves open.
     */
    fun sendCheatPermission(player: Player) {
        val config = plugin.pluginConfig
        val allowedMethods = buildList {
            if (config.cheatEnabled) {
                add("jei.chat.error.no.cheat.permission.op")
                if (config.cheatAllowCreative) add("jei.chat.error.no.cheat.permission.creative")
            }
        }

        val buf = registryBuf()
        try {
            buf.writeBoolean(hasCheatPermission(player))
            buf.writeVarInt(allowedMethods.size)
            allowedMethods.forEach(buf::writeUtf)
            player.sendPluginMessage(plugin, Channels.Jei.CHEAT_PERMISSION, buf.copyBytes())
        } finally {
            buf.release()
        }
    }

    private fun denyCheat(player: Player) {
        plugin.logger.warning("${player.name} tried to use cheat mode without permission.")
        player.sendMessage(plugin.localeManager.component("cheat.no-permission"))
        // Correct the client's idea of its own permission so it stops offering the action.
        sendCheatPermission(player)
    }

    private fun onDeleteItem(player: Player, message: ByteArray, optional: Boolean) {
        if (!hasCheatPermission(player)) return denyCheat(player)

        val carried = readItemStack(message, optional)
        val handle = player.nms
        val menu = handle.containerMenu
        val held = menu.carried
        // Only clear what the client thinks it is holding; anything else means the two views have
        // drifted apart and deleting would destroy an item the player did not drag onto the list.
        if (!held.isEmpty && held.item === carried.item) {
            menu.carried = ItemStack.EMPTY
            menu.broadcastChanges()
        }
    }

    private fun onReiDeleteItem(player: Player) {
        if (!hasCheatPermission(player)) return denyCheat(player)

        val menu = player.nms.containerMenu
        if (!menu.carried.isEmpty) {
            menu.carried = ItemStack.EMPTY
            menu.broadcastChanges()
        }
    }

    private fun onJeiGiveItem(player: Player, message: ByteArray) {
        if (!hasCheatPermission(player)) return denyCheat(player)

        val buf = registryBuf(message)
        try {
            val stack = ItemStack.STREAM_CODEC.decode(buf)
            // GiveMode: 0 = INVENTORY, 1 = MOUSE_PICKUP.
            val toCursor = buf.readVarInt() == 1
            give(player.nms, stack, toCursor)
        } finally {
            buf.release()
        }
    }

    private fun onReiCreateItem(player: Player, message: ByteArray, toCursor: Boolean) {
        if (!hasCheatPermission(player)) return denyCheat(player)

        val stack = readItemStack(message, optional = true)
        give(player.nms, stack, toCursor)
    }

    private fun give(handle: ServerPlayer, stack: ItemStack, toCursor: Boolean) {
        if (stack.isEmpty) return

        if (toCursor) {
            val menu = handle.containerMenu
            val carried = menu.carried
            val given: Int
            if (!carried.isEmpty && ItemStack.isSameItemSameComponents(carried, stack)) {
                val newCount = minOf(carried.maxStackSize, carried.count + stack.count)
                given = newCount - carried.count
                if (given > 0) carried.count = newCount
            } else if (!carried.isEmpty) {
                // The cursor already holds something else; replacing it would destroy it.
                return
            } else {
                menu.carried = stack.copy()
                given = stack.count
            }
            if (given > 0) {
                menu.broadcastChanges()
                notifyGive(handle, stack.copyWithCount(given))
            }
        } else {
            val copy = stack.copy()
            if (!handle.inventory.add(copy)) {
                handle.drop(copy, false)
            }
            handle.inventoryMenu.broadcastChanges()
            notifyGive(handle, stack.copy())
        }
    }

    private fun onSetHotbarItem(player: Player, message: ByteArray, optional: Boolean) {
        if (!hasCheatPermission(player)) return denyCheat(player)

        val buf = registryBuf(message)
        val stack: ItemStack
        val slot: Int
        try {
            stack = if (optional) ItemStack.OPTIONAL_STREAM_CODEC.decode(buf) else ItemStack.STREAM_CODEC.decode(buf)
            slot = buf.readVarInt()
        } finally {
            buf.release()
        }

        if (stack.isEmpty || !Inventory.isHotbarSlot(slot)) return

        val handle = player.nms
        if (ItemStack.matches(handle.inventory.getItem(slot), stack)) return

        val copy = stack.copy()
        handle.inventory.setItem(slot, stack)
        handle.level().playSound(
            null, handle.x, handle.y, handle.z,
            SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS,
            0.2f, ((handle.random.nextFloat() - handle.random.nextFloat()) * 0.7f + 1.0f) * 2.0f,
        )
        handle.inventoryMenu.broadcastChanges()
        notifyGive(handle, copy)
    }

    /** Logs the cheat the same way `/give` does, so it lands in the server log and admin feed. */
    private fun notifyGive(handle: ServerPlayer, stack: ItemStack) {
        handle.createCommandSourceStack().sendSuccess(
            {
                Component.translatable(
                    "commands.give.success.single",
                    stack.count,
                    stack.displayName,
                    handle.displayName,
                )
            },
            true,
        )
    }

    // ---------------------------------------------------------------- recipe transfer

    private fun onRecipeTransfer(player: Player, message: ByteArray, counted: Boolean) {
        if (!plugin.pluginConfig.recipeTransferEnabled) return

        val buf = registryBuf(message)
        val operations: List<TransferOperation>
        val craftingSlotIds: List<Int>
        val inventorySlotIds: List<Int>
        val maxTransfer: Boolean
        val requireCompleteSets: Boolean
        try {
            operations = List(buf.readVarInt()) {
                val from = buf.readVarInt()
                val to = buf.readVarInt()
                val count = if (counted) buf.readVarInt() else 1
                TransferOperation(from, to, count)
            }
            craftingSlotIds = List(buf.readVarInt()) { buf.readVarInt() }
            inventorySlotIds = List(buf.readVarInt()) { buf.readVarInt() }
            maxTransfer = buf.readBoolean()
            requireCompleteSets = buf.readBoolean()
        } finally {
            buf.release()
        }

        val handle = player.nms
        val craftingSlots = resolveSlots(handle, craftingSlotIds) ?: return
        val inventorySlots = resolveSlots(handle, inventorySlotIds) ?: return

        transfer.setItems(handle, operations, craftingSlots, inventorySlots, maxTransfer, requireCompleteSets)
    }

    /**
     * REI's transfer. Unlike JEI's, the payload is an NBT blob in REI's own format naming the
     * ingredient options and the slots to read and write; the matching and moving happen here.
     */
    private fun onReiRecipeTransfer(player: Player, message: ByteArray) {
        if (!plugin.pluginConfig.recipeTransferEnabled) return

        val registries = minecraftServer.registryAccess()
        val buf = registryBuf(message)
        val stacked: Boolean
        val data: CompoundTag
        try {
            Identifier.STREAM_CODEC.decode(buf) // recipe category; the slots carry all we need
            stacked = buf.readBoolean()
            data = ByteBufCodecs.COMPOUND_TAG.decode(buf)
        } finally {
            buf.release()
        }

        val handle = player.nms
        try {
            val request = ReiTransferPayload.decode(data, registries)
            if (plugin.pluginConfig.debug) {
                plugin.logger.info(
                    "[rei] transfer from ${player.name}: stacked=$stacked, " +
                        "inputs=${request.inputs.size} (${request.inputs.sumOf { it.stacks.size }} stacks), " +
                        "inputSlots=${request.inputSlots.size}, inventorySlots=${request.inventorySlots.size}, " +
                        "menu=${handle.containerMenu.javaClass.simpleName}"
                )
            }
            val outcome = reiTransfer.fillInputSlots(handle, request, stacked)
            if (plugin.pluginConfig.debug) plugin.logger.info("[rei] outcome: $outcome")
        } catch (e: NotEnoughMaterialsException) {
            // The player simply does not have the items; REI shows that itself.
            if (plugin.pluginConfig.debug) plugin.logger.info("[rei] outcome: not enough materials")
        } catch (e: IllegalStateException) {
            // These messages are REI translation keys, which is what its client expects to render.
            handle.sendSystemMessage(
                Component.translatable(e.message ?: "error.rei.internal.error").withStyle(ChatFormatting.RED)
            )
        }
    }

    private fun resolveSlots(handle: ServerPlayer, ids: List<Int>): List<Slot>? {
        val container = handle.containerMenu
        if (ids.size > container.slots.size) {
            plugin.logger.warning("Recipe transfer lists more slots than the open container has.")
            return null
        }
        return ids.map { id ->
            if (id < 0 || id >= container.slots.size) {
                plugin.logger.warning("Recipe transfer references slot $id, outside the open container.")
                return null
            }
            container.getSlot(id)
        }
    }

    // ---------------------------------------------------------------- buffers

    private fun registryBuf(): RegistryFriendlyByteBuf = minecraftServer.registryAccess().newBuf()

    private fun registryBuf(bytes: ByteArray): RegistryFriendlyByteBuf =
        minecraftServer.registryAccess().readBuf(bytes)

    private fun readItemStack(message: ByteArray, optional: Boolean): ItemStack {
        val buf = registryBuf(message)
        return try {
            if (optional) ItemStack.OPTIONAL_STREAM_CODEC.decode(buf) else ItemStack.STREAM_CODEC.decode(buf)
        } finally {
            buf.release()
        }
    }

    /** Forgets any half-assembled REI payload from a player who left mid-transfer. */
    fun onPlayerQuit(player: Player) {
        reiFrames.forget(player.uniqueId)
    }

    companion object {
        const val CHEAT_PERMISSION = "jreiproxyserver.cheat"

        /** Decode failures quote raw payload bytes; stripping them keeps one failure to one line. */
        private val CONTROL_CHARACTERS = Regex("""[\p{Cntrl}�]""")

        private val REI_CHANNELS = Channels.Rei.INCOMING.toSet()
    }
}
