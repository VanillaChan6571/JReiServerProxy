package gg.nekohosting.vanilla.jreiproxyserver.network.rei

import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.BitSet

/** An item identity for matching: the item plus its component patch, ignoring stack size. */
data class ItemKey(val item: Holder<Item>, val patch: DataComponentPatch)

/** One recipe slot, carrying its position in the display and every stack that would satisfy it. */
data class FinderIngredient(val index: Int, val elements: List<ItemKey>)

/**
 * Works out which item to put in each recipe slot, and how many times the recipe can be made.
 *
 * A port of REI's `RecipeFinder`, itself the same bipartite matching vanilla's recipe book uses.
 * The naive alternative — walk the slots and take the first inventory item that fits — gets stuck
 * whenever two slots accept overlapping item sets: it can hand slot A the only item slot B could
 * have used and then wrongly report the recipe as uncraftable. Matching the client's algorithm also
 * means the server fills the grid with what the player was shown.
 */
class RecipeFinder {

    private val amounts = HashMap<ItemKey, Int>()

    fun contains(key: ItemKey): Boolean = (amounts[key] ?: 0) > 0

    private fun containsAtLeast(key: ItemKey, count: Int): Boolean = (amounts[key] ?: 0) >= count

    private fun take(key: ItemKey, amount: Int) {
        val had = amounts.getOrDefault(key, 0)
        require(had >= amount) { "Took $amount items, but only had $had" }
        amounts[key] = had - amount
    }

    private fun put(key: ItemKey, amount: Int) {
        amounts[key] = (amounts[key] ?: 0) + amount
    }

    /** Adds a stack the player could craft with; containers and other unusable items are skipped. */
    fun addNormalItem(stack: ItemStack) {
        if (!Inventory.isUsableForCrafting(stack)) return
        if (stack.isEmpty) return
        put(keyOf(stack), minOf(stack.maxStackSize, stack.count))
    }

    /**
     * Reports whether `crafts` repetitions are possible, and if `output` is given, emits the chosen
     * stack per recipe slot in slot order — with an empty stack for each skipped position, so the
     * result lines up with the grid.
     */
    fun findRecipe(ingredients: List<List<ItemStack>>, crafts: Int, output: ((ItemStack) -> Unit)?): Boolean =
        Filter(toIngredients(ingredients)).tryPick(crafts, flatten(output))

    /** The largest number of repetitions possible, up to `maxCrafts`. */
    fun countRecipeCrafts(ingredients: List<List<ItemStack>>, maxCrafts: Int): Int =
        Filter(toIngredients(ingredients)).tryPickAll(maxCrafts)

    private fun keyOf(stack: ItemStack) = ItemKey(stack.typeHolder(), stack.componentsPatch)

    private fun toIngredients(ingredients: List<List<ItemStack>>): List<FinderIngredient> =
        ingredients.mapIndexedNotNull { index, stacks ->
            if (stacks.isEmpty()) null else FinderIngredient(index, stacks.map(::keyOf))
        }

    /**
     * Turns per-ingredient results back into one stack per grid position, padding the positions the
     * recipe leaves empty.
     */
    private fun flatten(output: ((ItemStack) -> Unit)?): ((ItemKey, FinderIngredient) -> Unit)? {
        if (output == null) return null
        var lastIndex = -1
        return { key, ingredient ->
            for (i in lastIndex + 1 until ingredient.index) output(ItemStack.EMPTY)
            output(ItemStack(key.item, 1, key.patch))
            lastIndex = ingredient.index
        }
    }

    private inner class Filter(private val ingredients: List<FinderIngredient>) {

        private val ingredientCount = ingredients.size
        private val items: List<ItemKey> = ingredients
            .flatMap { it.elements }
            .distinct()
            .filter { contains(it) }
        private val itemCount = items.size
        private val data = BitSet(
            ingredientCount + itemCount + ingredientCount + ingredientCount * itemCount * 2
        )
        private val path = ArrayList<Int>()

        init {
            for (i in 0 until ingredientCount) {
                val elements = ingredients[i].elements
                for (j in 0 until itemCount) {
                    if (items[j] in elements) setConnection(j, i)
                }
            }
        }

        fun tryPick(crafts: Int, output: ((ItemKey, FinderIngredient) -> Unit)?): Boolean {
            if (crafts <= 0) return true

            var assigned = 0
            while (true) {
                val assignment = tryAssigningNewItem(crafts)
                if (assignment == null) {
                    val complete = assigned == ingredientCount
                    // Only a complete match describes a recipe worth reporting; a partial one is
                    // unwound in silence.
                    val emit = if (complete) output else null
                    clearAllVisited()
                    clearSatisfied()

                    // Hand every reservation back, so the finder is reusable for the next attempt.
                    for (ingredient in 0 until ingredientCount) {
                        for (item in 0 until itemCount) {
                            if (isAssigned(item, ingredient)) {
                                unassign(item, ingredient)
                                put(items[item], crafts)
                                emit?.invoke(items[item], ingredients[ingredient])
                                break
                            }
                        }
                    }
                    return complete
                }

                take(items[assignment[0]], crafts)
                setSatisfied(assignment[assignment.size - 1])
                assigned++

                for (step in 0 until assignment.size - 1) {
                    if (isPathIndexItem(step)) {
                        assign(assignment[step], assignment[step + 1])
                    } else {
                        unassign(assignment[step + 1], assignment[step])
                    }
                }
            }
        }

        /** Binary-searches the largest craft count that still succeeds. */
        fun tryPickAll(maxCrafts: Int): Int {
            var low = 0
            var high = minOf(maxCrafts, minIngredientCount()) + 1
            while (true) {
                val mid = (low + high) / 2
                if (tryPick(mid, null)) {
                    if (high - low <= 1) return mid
                    low = mid
                } else {
                    high = mid
                }
            }
        }

        private fun minIngredientCount(): Int {
            var min = Int.MAX_VALUE
            for (ingredient in ingredients) {
                var best = 0
                for (element in ingredient.elements) best = maxOf(best, amounts[element] ?: 0)
                if (min > 0) min = minOf(min, best)
            }
            return min
        }

        private fun tryAssigningNewItem(crafts: Int): List<Int>? {
            clearAllVisited()
            for (item in 0 until itemCount) {
                if (containsAtLeast(items[item], crafts)) {
                    findNewItemAssignmentPath(item)?.let { return it }
                }
            }
            return null
        }

        private fun findNewItemAssignmentPath(start: Int): List<Int>? {
            path.clear()
            visitItem(start)
            path.add(start)

            while (path.isNotEmpty()) {
                val depth = path.size
                val head = path[depth - 1]
                if (isPathIndexItem(depth - 1)) {
                    for (ingredient in 0 until ingredientCount) {
                        if (!hasVisitedIngredient(ingredient) && hasConnection(head, ingredient) &&
                            !isAssigned(head, ingredient)
                        ) {
                            visitIngredient(ingredient)
                            path.add(ingredient)
                            break
                        }
                    }
                } else {
                    if (!isSatisfied(head)) return ArrayList(path)
                    for (item in 0 until itemCount) {
                        if (!hasVisitedItem(item) && isAssigned(item, head)) {
                            visitItem(item)
                            path.add(item)
                            break
                        }
                    }
                }

                // Nothing was added, so this branch is a dead end: step back out of it.
                if (path.size == depth) path.removeAt(path.size - 1)
            }
            return null
        }

        private fun isPathIndexItem(index: Int) = (index and 1) == 0

        // The BitSet is one flat allocation carved into five regions.
        private val visitedIngredientOffset get() = 0
        private val visitedItemOffset get() = ingredientCount
        private val satisfiedOffset get() = visitedItemOffset + itemCount
        private val connectionOffset get() = satisfiedOffset + ingredientCount
        private val residualOffset get() = connectionOffset + ingredientCount * itemCount

        private fun isSatisfied(i: Int) = data.get(satisfiedOffset + i)
        private fun setSatisfied(i: Int) = data.set(satisfiedOffset + i)
        private fun clearSatisfied() = data.clear(satisfiedOffset, satisfiedOffset + ingredientCount)

        private fun setConnection(item: Int, ingredient: Int) =
            data.set(connectionOffset + item * ingredientCount + ingredient)

        private fun hasConnection(item: Int, ingredient: Int) =
            data.get(connectionOffset + item * ingredientCount + ingredient)

        private fun isAssigned(item: Int, ingredient: Int) =
            data.get(residualOffset + item * ingredientCount + ingredient)

        private fun assign(item: Int, ingredient: Int) =
            data.set(residualOffset + item * ingredientCount + ingredient)

        private fun unassign(item: Int, ingredient: Int) =
            data.clear(residualOffset + item * ingredientCount + ingredient)

        private fun visitIngredient(i: Int) = data.set(visitedIngredientOffset + i)
        private fun hasVisitedIngredient(i: Int) = data.get(visitedIngredientOffset + i)
        private fun visitItem(i: Int) = data.set(visitedItemOffset + i)
        private fun hasVisitedItem(i: Int) = data.get(visitedItemOffset + i)

        private fun clearAllVisited() {
            data.clear(visitedIngredientOffset, visitedIngredientOffset + ingredientCount)
            data.clear(visitedItemOffset, visitedItemOffset + itemCount)
        }
    }
}
