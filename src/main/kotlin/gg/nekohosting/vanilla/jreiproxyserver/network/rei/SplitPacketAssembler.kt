package gg.nekohosting.vanilla.jreiproxyserver.network.rei

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Undoes Architectury's `SplitPacketTransformer` framing, which every REI packet goes through.
 *
 * The transformer prefixes a state byte to *all* payloads, not just oversized ones — a small packet
 * arrives as `ONLY` followed by its data. Reading a REI payload without stripping that byte
 * misparses every field, so this sits in front of all REI channels.
 *
 * A payload larger than the client's 32 KB budget arrives as `START` (carrying the part count),
 * then `PART`s, then `END`, all on the same channel, and is reassembled here in arrival order.
 */
class SplitPacketAssembler {

    private data class Key(val player: UUID, val channel: String)

    private class Pending(val expected: Int) {
        val parts = ArrayList<ByteArray>()
    }

    // Plugin messages arrive on the region thread owning each player, so several players can be
    // mid-reassembly on different threads at once.
    private val pending = ConcurrentHashMap<Key, Pending>()

    /**
     * Returns the complete payload, or null when this message was only a fragment and more are
     * still to come.
     */
    fun accept(player: UUID, channel: String, message: ByteArray): ByteArray? {
        // Architectury hands every payload to Minecraft as a plain byte array, which serialises as
        // a VarInt length followed by the bytes. That prefix wraps the split framing below, so it
        // has to come off first or the length's own first byte reads as the frame state.
        val framed = unwrapByteArray(message) ?: return null
        if (framed.isEmpty()) return null

        val key = Key(player, channel)
        val body = framed.copyOfRange(1, framed.size)

        return when (framed[0].toInt()) {
            ONLY -> body

            START -> {
                if (body.size < Int.SIZE_BYTES) return null
                val expected = readInt(body)
                val state = Pending(expected)
                state.parts.add(body.copyOfRange(Int.SIZE_BYTES, body.size))
                pending[key] = state
                null
            }

            PART -> {
                pending[key]?.parts?.add(body)
                null
            }

            END -> {
                val state = pending.remove(key) ?: return null
                state.parts.add(body)
                // A mismatched count means fragments were lost; the payload cannot be trusted.
                if (state.parts.size != state.expected) null else concat(state.parts)
            }

            else -> null
        }
    }

    /** Drops any half-assembled payload for a player who left mid-transfer. */
    fun forget(player: UUID) {
        pending.keys.removeIf { it.player == player }
    }

    /** Strips the VarInt-length prefix of a byte-array payload, or null if it is malformed. */
    private fun unwrapByteArray(message: ByteArray): ByteArray? {
        var value = 0
        var shift = 0
        var at = 0
        while (at < message.size) {
            val byte = message[at].toInt()
            value = value or ((byte and 0x7F) shl shift)
            at++
            if (byte and 0x80 == 0) {
                // A length that does not match what actually arrived means this is not the payload
                // shape we expect; dropping it is safer than reading past the end.
                return if (value < 0 || at + value > message.size) null else message.copyOfRange(at, at + value)
            }
            shift += 7
            if (shift >= 32) return null
        }
        return null
    }

    private fun readInt(bytes: ByteArray): Int =
        (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private companion object {
        const val START = 0x0
        const val PART = 0x1
        const val END = 0x2
        const val ONLY = 0x3
    }
}
