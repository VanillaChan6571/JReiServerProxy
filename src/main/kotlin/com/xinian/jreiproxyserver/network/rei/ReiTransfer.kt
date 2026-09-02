package com.xinian.jreiproxyserver.network.rei

import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/** The recipe cannot be made from what the player is carrying. */
class NotEnoughMaterialsException : RuntimeException(null, null, false, false)

/** Why a transfer stopped, so a failure is never silent in the log. */
sealed interface ReiTransferOutcome {
    data class Filled(val crafts: Int, val slotsFilled: Int) : ReiTransferOutcome
    data object NoIngredients : ReiTransferOutcome
    data object NoInputSlots : ReiTransferOutcome
    data class CouldNotPlan(val stage: String) : ReiTransferOutcome
}

/**
 * The server half of REI's "+" button.
 *
 * A port of REI's `InputSlotCrafter`/`NewInputSlotCrafter`. The client sends which slots hold the
 * ingredients and which hold the inventory; the server is what actually moves items, because the
 * client's view can be stale and slots can refuse what they are handed.
 *
 * Items are never destroyed: the grid is emptied back into the inventory first, and anything that
 * cannot be placed afterwards is returned the same way.
 */
class ReiTransfer(private val debug: (String) -> Unit = {}) {

    fun fillInputSlots(
        player: ServerPlayer,
        request: ReiTransferRequest,
        stacked: Boolean,
    ): ReiTransferOutcome {
        val inputSlots = request.inputSlots
        val inventorySlots = request.inventorySlots

        if (inputSlots.isEmpty()) return ReiTransferOutcome.NoInputSlots
        if (request.inputs.none { it.stacks.isNotEmpty() }) return ReiTransferOutcome.NoIngredients

        // Whatever is already on the grid goes back to the player before anything is taken.
        cleanInputs(player, inputSlots, inventorySlots)

        val finder = RecipeFinder()
        for (slot in inventorySlots) finder.addNormalItem(slot.get(player))

        val ingredients = buildIngredients(request)

        if (!finder.findRecipe(ingredients, 1, null)) {
            cleanInputs(player, inputSlots, inventorySlots)
            markDirty(player)
            throw NotEnoughMaterialsException()
        }

        val outcome = fill(player, finder, ingredients, inputSlots, inventorySlots, stacked)
        markDirty(player)
        return outcome
    }

    /**
     * Lays the ingredients out by display index.
     *
     * The payload only carries the slots that actually have ingredients, but the grid is filled
     * positionally, so the gaps have to be restored as empty entries or every ingredient after a
     * gap lands one slot too early.
     */
    private fun buildIngredients(request: ReiTransferRequest): List<List<ItemStack>> {
        val size = (request.inputs.maxOfOrNull { it.index } ?: -1) + 1
        val laidOut = MutableList(size) { emptyList<ItemStack>() }
        for (input in request.inputs) laidOut[input.index] = input.stacks
        return laidOut
    }

    private fun fill(
        player: ServerPlayer,
        finder: RecipeFinder,
        ingredients: List<List<ItemStack>>,
        inputSlots: List<ReiSlot>,
        inventorySlots: List<ReiSlot>,
        stacked: Boolean,
    ): ReiTransferOutcome {
        val possible = finder.countRecipeCrafts(ingredients, Int.MAX_VALUE)
        val requested = if (stacked) maxOf(1, possible) else 1
        debug("possible=$possible requested=$requested stacked=$stacked")

        val chosen = ArrayList<ItemStack>()
        if (!finder.findRecipe(ingredients, requested, chosen::add)) {
            return ReiTransferOutcome.CouldNotPlan("requested=$requested")
        }

        // No grid slot may be asked to hold more than that item stacks to.
        var crafts = requested
        for (stack in chosen) {
            if (!stack.isEmpty) crafts = minOf(crafts, stack.maxStackSize)
        }

        chosen.clear()
        if (!finder.findRecipe(ingredients, crafts, chosen::add)) {
            return ReiTransferOutcome.CouldNotPlan("crafts=$crafts")
        }

        cleanInputs(player, inputSlots, inventorySlots)
        val filled = alignToGrid(player, inputSlots, inventorySlots, chosen, crafts)
        return ReiTransferOutcome.Filled(crafts, filled)
    }

    private fun alignToGrid(
        player: ServerPlayer,
        inputSlots: List<ReiSlot>,
        inventorySlots: List<ReiSlot>,
        chosen: List<ItemStack>,
        crafts: Int,
    ): Int {
        var filled = 0
        val wanted = chosen.iterator()
        for (slot in inputSlots) {
            if (!wanted.hasNext()) break
            val target = wanted.next()
            if (target.isEmpty) continue
            repeat(crafts) { if (fillOne(player, slot, target, inventorySlots)) filled++ }
        }
        return filled
    }

    private fun fillOne(
        player: ServerPlayer,
        slot: ReiSlot,
        wanted: ItemStack,
        inventorySlots: List<ReiSlot>,
    ): Boolean {
        val source = findInInventory(player, wanted, inventorySlots) ?: return false
        val available = source.get(player).copy()
        if (available.isEmpty) return false

        if (available.count > 1) source.take(player, 1) else source.set(player, ItemStack.EMPTY)
        available.count = 1

        if (!slot.canPlace(player, available)) {
            // The item is already out of the inventory at this point, so it has to go back —
            // returning here without stowing it, as REI's own handler does, destroys it.
            if (!dump(player, available, inventorySlots) && !player.inventory.add(available)) {
                player.drop(available, false)
            }
            return false
        }

        val existing = slot.get(player)
        if (existing.isEmpty) slot.set(player, available) else existing.grow(1)
        return true
    }

    /**
     * Finds an inventory slot holding the wanted item.
     *
     * Damaged, enchanted and renamed stacks are skipped deliberately — REI does the same, so that a
     * transfer never silently consumes a player's named or enchanted tool.
     */
    private fun findInInventory(player: ServerPlayer, wanted: ItemStack, inventorySlots: List<ReiSlot>): ReiSlot? {
        var rejected = false
        for (slot in inventorySlots) {
            val candidate = slot.get(player)
            if (candidate.isEmpty || !ItemStack.isSameItemSameComponents(wanted, candidate)) continue
            if (candidate.isDamaged || candidate.isEnchanted || candidate.has(DataComponents.CUSTOM_NAME)) continue
            if (slot.allowModification(player)) return slot
            rejected = true
        }
        check(!rejected) { "Unable to take item from inventory due to slot not allowing modification!" }
        return null
    }

    /** Empties the grid back into the player's inventory, one item at a time. */
    private fun cleanInputs(player: ServerPlayer, inputSlots: List<ReiSlot>, inventorySlots: List<ReiSlot>) {
        for (slot in inputSlots) {
            check(slot.allowModification(player)) { "Recipe slot $slot cannot be modified." }
            while (true) {
                val remaining = slot.get(player)
                if (remaining.isEmpty) break
                val single = remaining.copy().also { it.count = 1 }
                check(dump(player, single, inventorySlots)) { "rei.rei.no.slot.in.inv" }
                slot.take(player, 1)
            }
        }
    }

    /** Puts one item back, topping up a partial stack before claiming an empty slot. */
    private fun dump(player: ServerPlayer, stack: ItemStack, inventorySlots: List<ReiSlot>): Boolean {
        val target = inventorySlots.firstOrNull { canStackAddMore(it.get(player), stack) }
            ?: inventorySlots.firstOrNull { it.get(player).isEmpty }
            ?: return false

        val merged = stack.copy()
        merged.count = target.get(player).count + stack.count
        target.set(player, merged)
        return true
    }

    private fun canStackAddMore(existing: ItemStack, stack: ItemStack): Boolean =
        !existing.isEmpty &&
            ItemStack.isSameItemSameComponents(existing, stack) &&
            existing.isStackable &&
            existing.count + stack.count <= existing.maxStackSize

    private fun markDirty(player: ServerPlayer) {
        player.inventory.setChanged()
        player.containerMenu.sendAllDataToRemote()
    }
}
