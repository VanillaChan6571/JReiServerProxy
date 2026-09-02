package com.xinian.jreiproxyserver.network

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.logging.Logger

/** One ingredient move the client asked for: `count` items from `inventorySlotId` to `craftingSlotId`. */
data class TransferOperation(val inventorySlotId: Int, val craftingSlotId: Int, val count: Int)

/**
 * The server half of JEI's "+" button.
 *
 * This is a port of JEI's own `BasicRecipeTransferHandlerServer`: the client sends slot ids and
 * counts, and the server is what actually moves the items. Anything simpler duplicates or voids
 * items, because the client's view of the container can be stale and slots can refuse what they are
 * handed.
 */
class RecipeTransfer(private val logger: Logger) {

    private data class Required(val recipeSlot: Slot, val hint: Slot, val stack: ItemStack)

    fun setItems(
        player: ServerPlayer,
        operations: List<TransferOperation>,
        craftingSlots: List<Slot>,
        inventorySlots: List<Slot>,
        maxTransfer: Boolean,
        requireCompleteSets: Boolean,
    ) {
        if (!validateSlots(player, operations, craftingSlots, inventorySlots)) return
        if (!canClearCraftingSlots(player, craftingSlots)) return

        val required = calculateRequiredTransfers(player, operations) ?: return

        // Only spread items across as many sets as possible when the player asked for it and the
        // handler allows partial sets.
        val transferAsCompleteSets = requireCompleteSets || !maxTransfer

        val taken = takeItemsFromInventory(
            player, required, craftingSlots, inventorySlots, transferAsCompleteSets, maxTransfer,
        )
        if (taken.isEmpty()) {
            logger.warning("Recipe transfer for ${player.name.string} removed no items from the inventory.")
            return
        }

        val cleared = clearCraftingGrid(player, craftingSlots)
        val remainder = putItemsIntoCraftingGrid(taken, requireCompleteSets)

        stowItems(player, inventorySlots, cleared)
        stowItems(player, inventorySlots, remainder)

        player.containerMenu.broadcastChanges()
    }

    private fun validateSlots(
        player: ServerPlayer,
        operations: List<TransferOperation>,
        craftingSlots: List<Slot>,
        inventorySlots: List<Slot>,
    ): Boolean {
        val container = player.containerMenu
        val slotCount = container.slots.size

        val invalidIds = operations
            .flatMap { listOf(it.inventorySlotId, it.craftingSlotId) }
            .distinct()
            .filter { it < 0 || it >= slotCount }
        if (invalidIds.isNotEmpty()) {
            logger.warning("Recipe transfer has out-of-range slot ids: $invalidIds")
            return false
        }

        val inventoryIndexes = inventorySlots.map { it.index }.toSet()
        val craftingIndexes = craftingSlots.map { it.index }.toSet()

        val destinationsOutsideGrid = operations
            .map { container.getSlot(it.craftingSlotId).index }
            .filter { it !in craftingIndexes }
        if (destinationsOutsideGrid.isNotEmpty()) {
            logger.warning("Recipe transfer targets slots outside the crafting grid: $destinationsOutsideGrid")
            return false
        }

        val sourcesOutsideInventory = operations
            .map { container.getSlot(it.inventorySlotId).index }
            .filter { it !in inventoryIndexes && it !in craftingIndexes }
        if (sourcesOutsideInventory.isNotEmpty()) {
            logger.warning("Recipe transfer sources slots outside the inventory: $sourcesOutsideInventory")
            return false
        }

        val overlapping = inventoryIndexes.intersect(craftingIndexes)
        if (overlapping.isNotEmpty()) {
            logger.warning("Recipe transfer lists the same slots as both inventory and crafting: $overlapping")
            return false
        }

        val fakeSlots = (craftingSlots + inventorySlots).filter { it.isFake }.map { it.index }
        if (fakeSlots.isNotEmpty()) {
            logger.warning("Recipe transfer references fake (recipe output) slots: $fakeSlots")
            return false
        }

        return true
    }

    private fun canClearCraftingSlots(player: ServerPlayer, craftingSlots: List<Slot>): Boolean {
        for (slot in craftingSlots) {
            val stack = slot.item
            if (!stack.isEmpty && (!slot.mayPickup(player) || !slot.mayPlace(stack))) {
                logger.warning("Crafting slot ${slot.index} holds an item that cannot be moved.")
                return false
            }
        }
        return true
    }

    private fun calculateRequiredTransfers(
        player: ServerPlayer,
        operations: List<TransferOperation>,
    ): List<Required>? {
        val container = player.containerMenu
        val required = ArrayList<Required>(operations.size)
        val targetSlotStacks = HashMap<Slot, ItemStack>()

        for (operation in operations) {
            val recipeSlot = container.getSlot(operation.craftingSlotId)
            val inventorySlot = container.getSlot(operation.inventorySlotId)

            if (!inventorySlot.allowModification(player)) {
                logger.warning("Recipe transfer source slot ${inventorySlot.index} cannot be taken from.")
                return null
            }
            val slotStack = inventorySlot.item
            if (slotStack.isEmpty) {
                logger.warning("Recipe transfer source slot ${inventorySlot.index} is empty.")
                return null
            }

            val stack = slotStack.copy()
            stack.count = operation.count
            if (!recipeSlot.mayPlace(stack)) {
                logger.warning("Crafting slot ${recipeSlot.index} does not accept the ingredient.")
                return null
            }

            val existing = targetSlotStacks.putIfAbsent(recipeSlot, stack)
            if (existing != null && !ItemStack.isSameItemSameComponents(existing, stack)) {
                logger.warning("Recipe transfer puts two different ingredients into slot ${recipeSlot.index}.")
                return null
            }

            required.add(Required(recipeSlot, inventorySlot, stack))
        }
        return required
    }

    private fun takeItemsFromInventory(
        player: ServerPlayer,
        required: List<Required>,
        craftingSlots: List<Slot>,
        inventorySlots: List<Slot>,
        transferAsCompleteSets: Boolean,
        maxTransfer: Boolean,
    ): Map<Slot, ItemStack> {
        if (!maxTransfer) {
            return removeOneSet(player, required, craftingSlots, inventorySlots, transferAsCompleteSets)
        }

        val remaining = ArrayList(required)
        val result = HashMap<Slot, ItemStack>(required.size)
        while (true) {
            removeFullRecipeSlots(remaining, result)
            if (remaining.isEmpty()) break

            val found = removeOneSet(player, remaining, craftingSlots, inventorySlots, transferAsCompleteSets)
            if (found.isEmpty()) break

            found.forEach { (slot, stack) -> merge(result, slot, stack) }
        }
        return result
    }

    private fun removeFullRecipeSlots(required: MutableList<Required>, result: Map<Slot, ItemStack>) {
        val full = HashSet<Slot>()
        for (transfer in required) {
            val resultStack = result[transfer.recipeSlot] ?: continue
            val requiredCount = required.filter { it.recipeSlot === transfer.recipeSlot }.sumOf { it.stack.count }
            val maxStackSize =
                if (transfer.recipeSlot.mayPlace(resultStack)) transfer.recipeSlot.getMaxStackSize(resultStack)
                else Int.MAX_VALUE
            if (resultStack.count + requiredCount > maxStackSize) {
                full.add(transfer.recipeSlot)
            }
        }
        required.removeIf { it.recipeSlot in full }
    }

    private fun removeOneSet(
        player: ServerPlayer,
        required: List<Required>,
        craftingSlots: List<Slot>,
        inventorySlots: List<Slot>,
        transferAsCompleteSets: Boolean,
    ): Map<Slot, ItemStack> {
        // Only needed when a partial set has to be rolled back.
        val originalContents = if (transferAsCompleteSets) HashMap<Slot, ItemStack>() else null
        val found = HashMap<Slot, ItemStack>(required.size)

        for (transfer in required) {
            val source = findSlotWithStack(player, transfer.stack, craftingSlots, inventorySlots, transfer.hint)
            if (source != null) {
                originalContents?.putIfAbsent(source, source.item.copy())
                val removed = source.safeTake(transfer.stack.count, Int.MAX_VALUE, player)
                merge(found, transfer.recipeSlot, removed)
            } else if (transferAsCompleteSets) {
                // The set cannot be completed, so undo everything taken for it.
                originalContents?.forEach { (slot, stack) -> slot.set(stack) }
                return emptyMap()
            }
        }
        return found
    }

    private fun clearCraftingGrid(player: ServerPlayer, craftingSlots: List<Slot>): List<ItemStack> {
        val cleared = ArrayList<ItemStack>()
        for (slot in craftingSlots) {
            if (!slot.mayPickup(player)) continue
            val item = slot.item
            if (!item.isEmpty && slot.mayPlace(item)) {
                cleared.add(slot.safeTake(Int.MAX_VALUE, Int.MAX_VALUE, player))
            }
        }
        return cleared
    }

    private fun putItemsIntoCraftingGrid(
        taken: Map<Slot, ItemStack>,
        requireCompleteSets: Boolean,
    ): List<ItemStack> {
        val slotStackLimit = if (!requireCompleteSets) {
            Int.MAX_VALUE
        } else {
            taken.entries.minOfOrNull { (slot, stack) ->
                if (slot.mayPlace(stack)) slot.getMaxStackSize(stack) else Int.MAX_VALUE
            } ?: Int.MAX_VALUE
        }

        val remainders = ArrayList<ItemStack>()
        taken.forEach { (slot, stack) ->
            val remainder = slot.safeInsert(stack, slotStackLimit)
            if (!remainder.isEmpty) remainders.add(remainder)
        }
        return remainders
    }

    private fun stowItems(player: ServerPlayer, inventorySlots: List<Slot>, stacks: List<ItemStack>) {
        for (stack in stacks) {
            val remainder = stowItem(player, inventorySlots, stack)
            if (!remainder.isEmpty && !player.inventory.add(remainder)) {
                player.drop(remainder, false)
            }
        }
    }

    private fun stowItem(player: ServerPlayer, slots: List<Slot>, stack: ItemStack): ItemStack {
        if (stack.isEmpty) return ItemStack.EMPTY

        var remainder = stack.copy()

        // Top up existing stacks before claiming an empty slot.
        for (slot in slots) {
            if (!slot.mayPickup(player)) continue
            val inventoryStack = slot.item
            if (!inventoryStack.isEmpty && inventoryStack.isStackable) {
                remainder = slot.safeInsert(remainder)
                if (remainder.isEmpty) return ItemStack.EMPTY
            }
        }

        for (slot in slots) {
            if (slot.item.isEmpty) {
                remainder = slot.safeInsert(remainder)
                if (remainder.isEmpty) return ItemStack.EMPTY
            }
        }

        return remainder
    }

    private fun findSlotWithStack(
        player: ServerPlayer,
        stack: ItemStack,
        craftingSlots: List<Slot>,
        inventorySlots: List<Slot>,
        hint: Slot,
    ): Slot? {
        if (isValidAndMatches(player, hint, stack)) return hint
        return craftingSlots.firstOrNull { isValidAndMatches(player, it, stack) }
            ?: inventorySlots.firstOrNull { isValidAndMatches(player, it, stack) }
    }

    private fun isValidAndMatches(player: ServerPlayer, slot: Slot, stack: ItemStack): Boolean {
        val contained = slot.item
        return ItemStack.isSameItemSameComponents(stack, contained) &&
            contained.count >= stack.count &&
            slot.allowModification(player)
    }

    private fun merge(result: MutableMap<Slot, ItemStack>, slot: Slot, stack: ItemStack) {
        val existing = result[slot]
        if (existing == null) {
            result[slot] = stack
        } else {
            existing.grow(stack.count)
        }
    }
}
