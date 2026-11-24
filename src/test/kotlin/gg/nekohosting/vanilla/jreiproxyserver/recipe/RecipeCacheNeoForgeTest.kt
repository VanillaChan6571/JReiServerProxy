package gg.nekohosting.vanilla.jreiproxyserver.recipe

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class RecipeCacheNeoForgeTest {

    private data class FakeHolder(
        val id: String,
        val typeKey: String?,
        val serializerLabel: String,
        val payload: ByteArray? = null,
        val failEncode: Boolean = false,
    )

    @Test
    fun `keeps all entries and deduplicates type keys when every holder encodes`() {
        val holders = listOf(
            FakeHolder("a", "minecraft:crafting", "minecraft:crafting_shaped", byteArrayOf(1, 2)),
            FakeHolder("b", "minecraft:crafting", "minecraft:crafting_shapeless", byteArrayOf(3)),
            FakeHolder("c", "minecraft:smelting", "minecraft:smelting", byteArrayOf(4, 5)),
        )

        val result = collect(holders)

        assertEquals(listOf("minecraft:crafting", "minecraft:smelting"), result.types.toList())
        assertEquals(3, result.encodedHolders.size)
        assertContentEquals(byteArrayOf(1, 2), result.encodedHolders[0])
        assertContentEquals(byteArrayOf(3), result.encodedHolders[1])
        assertContentEquals(byteArrayOf(4, 5), result.encodedHolders[2])
        assertEquals(emptyList(), result.skippedLabels)
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `skips unregistered serializers without dropping valid recipes`() {
        val holders = listOf(
            FakeHolder("valid1", "minecraft:crafting", "minecraft:crafting_shaped", byteArrayOf(10)),
            FakeHolder("ce1", null, "CustomRepairSerializer", byteArrayOf(99)),
            FakeHolder("ce2", null, "CustomRepairSerializer", byteArrayOf(98)),
            FakeHolder("ce3", null, "CustomDyeSerializer", byteArrayOf(97)),
            FakeHolder("valid2", "minecraft:smelting", "minecraft:smelting", byteArrayOf(20)),
        )

        val result = collect(holders)

        assertEquals(listOf("minecraft:crafting", "minecraft:smelting"), result.types.toList())
        assertEquals(2, result.encodedHolders.size)
        assertContentEquals(byteArrayOf(10), result.encodedHolders[0])
        assertContentEquals(byteArrayOf(20), result.encodedHolders[1])
        assertEquals(listOf("CustomRepairSerializer", "CustomDyeSerializer"), result.skippedLabels)
        assertEquals(3, result.skippedCount)
    }

    @Test
    fun `skips entry whose stream encoding throws without poisoning types or remaining entries`() {
        val holders = listOf(
            FakeHolder("bad", "minecraft:stonecutting", "broken:serializer", failEncode = true),
            FakeHolder("good", "minecraft:crafting", "minecraft:crafting_shaped", byteArrayOf(7, 8)),
        )

        val result = collect(holders)

        assertEquals(listOf("minecraft:crafting"), result.types.toList())
        assertEquals(1, result.encodedHolders.size)
        assertContentEquals(byteArrayOf(7, 8), result.encodedHolders.single())
        assertEquals(listOf("broken:serializer"), result.skippedLabels)
        assertEquals(1, result.skippedCount)
    }

    private fun collect(holders: List<FakeHolder>): EncodableCollection<String> =
        collectEncodableEntries(
            holders = holders,
            keyOf = { it.typeKey },
            unregisteredLabel = { it.serializerLabel },
            encodeLabel = { it.serializerLabel },
            encode = {
                if (it.failEncode) throw IllegalArgumentException("Can't find id")
                it.payload ?: byteArrayOf()
            },
        )
}
