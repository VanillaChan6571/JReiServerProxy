package gg.nekohosting.vanilla.jreiproxyserver.network.rei

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.ceil

/**
 * Applies Architectury 21's outbound `SplitPacketTransformer` framing and
 * `BufCustomPacketPayload` byte-array envelope.
 *
 * Paper sends this as a raw/discarded custom payload, so Architectury's
 * registered stream codec is not available to add the outer VarInt length.
 */
object ReiSplitPacketFramer {

    fun frame(channel: String, payload: ByteArray): List<ByteArray> {
        val maxData = MAX_CUSTOM_PAYLOAD - 1 - PROTOCOL_HEADROOM -
            channel.toByteArray(StandardCharsets.UTF_8).size

        if (payload.size <= maxData) {
            return listOf(wrapByteArray(byteArrayOf(ONLY) + payload))
        }

        val partData = maxData - Int.SIZE_BYTES
        val parts = ceil(payload.size / partData.toDouble()).toInt()
        val frames = ArrayList<ByteArray>(parts)
        var offset = 0

        for (index in 0 until parts) {
            val count = minOf(partData, payload.size - offset)
            val header = if (index == 0) 1 + Int.SIZE_BYTES else 1
            val frame = ByteArray(header + count)
            frame[0] = when (index) {
                0 -> START
                parts - 1 -> END
                else -> PART
            }
            if (index == 0) {
                ByteBuffer.wrap(frame, 1, Int.SIZE_BYTES).putInt(parts)
            }
            payload.copyInto(frame, header, offset, offset + count)
            frames.add(wrapByteArray(frame))
            offset += count
        }

        return frames
    }

    private fun wrapByteArray(bytes: ByteArray): ByteArray {
        var remaining = bytes.size
        var prefixSize = 1
        while (remaining >= 0x80) {
            remaining = remaining ushr 7
            prefixSize++
        }

        val wrapped = ByteArray(prefixSize + bytes.size)
        remaining = bytes.size
        var index = 0
        do {
            var next = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            wrapped[index++] = next.toByte()
        } while (remaining != 0)

        bytes.copyInto(wrapped, prefixSize)
        return wrapped
    }

    private const val MAX_CUSTOM_PAYLOAD = 1_048_576
    private const val PROTOCOL_HEADROOM = 20

    private const val START: Byte = 0x0
    private const val PART: Byte = 0x1
    private const val END: Byte = 0x2
    private const val ONLY: Byte = 0x3
}
