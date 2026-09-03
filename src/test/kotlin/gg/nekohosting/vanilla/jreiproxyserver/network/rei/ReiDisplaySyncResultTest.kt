package gg.nekohosting.vanilla.jreiproxyserver.network.rei

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReiDisplaySyncResultTest {

    @Test
    fun `complete requires an encoded packet and no skipped displays`() {
        assertTrue(result(bytes = byteArrayOf(1), skipped = 0).complete)
        assertFalse(result(bytes = byteArrayOf(), skipped = 0).complete)
        assertFalse(result(bytes = byteArrayOf(1), skipped = 1).complete)
    }

    private fun result(bytes: ByteArray, skipped: Int) = ReiDisplaySyncEncoder.Result(
        bytes = bytes,
        displays = 1,
        serializers = 1,
        skippedDisplays = skipped,
        skippedTypes = if (skipped == 0) emptySet() else setOf("UnsupportedDisplay"),
    )
}
