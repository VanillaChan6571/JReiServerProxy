package gg.nekohosting.vanilla.jreiproxyserver.network.rei

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ReiSplitPacketFramerTest {

    @Test
    fun `empty and boundary payloads use an ONLY frame`() {
        val empty = ReiSplitPacketFramer.frame(CHANNEL, ByteArray(0))
        assertEquals(1, empty.size)
        assertContentEquals(byteArrayOf(ONLY), unwrapByteArray(empty.single()))

        val payload = patternedBytes(maxData)
        val boundary = ReiSplitPacketFramer.frame(CHANNEL, payload)
        assertEquals(1, boundary.size)
        val frame = unwrapByteArray(boundary.single())
        assertEquals(ONLY, frame[0])
        assertContentEquals(payload, frame.copyOfRange(1, frame.size))
    }

    @Test
    fun `first byte above the boundary produces START and END frames`() {
        val payload = patternedBytes(maxData + 1)
        val messages = ReiSplitPacketFramer.frame(CHANNEL, payload)
        val frames = messages.map(::unwrapByteArray)

        assertEquals(2, frames.size)
        assertEquals(START, frames[0][0])
        assertEquals(2, ByteBuffer.wrap(frames[0], 1, Int.SIZE_BYTES).int)
        assertEquals(END, frames[1][0])

        assertContentEquals(payload.copyOfRange(0, partData), frames[0].copyOfRange(5, frames[0].size))
        assertContentEquals(payload.copyOfRange(partData, payload.size), frames[1].copyOfRange(1, frames[1].size))
    }

    @Test
    fun `multi-part payload uses PART frames and reassembles byte-for-byte`() {
        val payload = patternedBytes(partData * 2 + 17)
        val messages = ReiSplitPacketFramer.frame(CHANNEL, payload)
        val frames = messages.map(::unwrapByteArray)

        assertEquals(3, frames.size)
        assertEquals(START, frames[0][0])
        assertEquals(3, ByteBuffer.wrap(frames[0], 1, Int.SIZE_BYTES).int)
        assertEquals(PART, frames[1][0])
        assertEquals(END, frames[2][0])

        val reassembled = frames.flatMapIndexed { index, frame ->
            frame.drop(if (index == 0) 5 else 1)
        }.toByteArray()
        assertContentEquals(payload, reassembled)
    }

    private fun unwrapByteArray(message: ByteArray): ByteArray {
        var length = 0
        var shift = 0
        var index = 0

        while (true) {
            val next = message[index++].toInt() and 0xff
            length = length or ((next and 0x7f) shl shift)
            if (next and 0x80 == 0) break
            shift += 7
        }

        assertEquals(message.size - index, length)
        return message.copyOfRange(index, message.size)
    }

    private fun patternedBytes(size: Int): ByteArray =
        ByteArray(size) { index -> (index * 31).toByte() }

    private companion object {
        const val CHANNEL = "roughlyenoughitems:sync_displays"
        val maxData = 1_048_576 - 1 - 20 - CHANNEL.toByteArray(StandardCharsets.UTF_8).size
        val partData = maxData - Int.SIZE_BYTES

        const val START: Byte = 0x0
        const val PART: Byte = 0x1
        const val END: Byte = 0x2
        const val ONLY: Byte = 0x3
    }
}
