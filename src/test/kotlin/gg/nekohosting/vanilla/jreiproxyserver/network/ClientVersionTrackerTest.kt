package gg.nekohosting.vanilla.jreiproxyserver.network

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientVersionTrackerTest {

    @Test
    fun `parses protocol version from valid ViaVersion proxy details payload`() {
        val payload = """{"specVersion":1,"platformName":"Velocity","platformVersion":"4.3.1","version":773,"versionName":"1.21.11","versionType":"RELEASE"}"""
            .toByteArray(StandardCharsets.UTF_8)

        assertEquals(773, parseProxyDetailsVersion(payload))
    }

    @Test
    fun `rejects missing, non-positive, unsupported specVersion, or malformed proxy details`() {
        assertNull(parseProxyDetailsVersion(ByteArray(0)))
        assertNull(parseProxyDetailsVersion("[]".toByteArray(StandardCharsets.UTF_8)))
        assertNull(parseProxyDetailsVersion("not json".toByteArray(StandardCharsets.UTF_8)))
        assertNull(parseProxyDetailsVersion("""{"specVersion":1}""".toByteArray(StandardCharsets.UTF_8)))
        assertNull(parseProxyDetailsVersion("""{"specVersion":1,"version":0}""".toByteArray(StandardCharsets.UTF_8)))
        assertNull(parseProxyDetailsVersion("""{"specVersion":1,"version":-1}""".toByteArray(StandardCharsets.UTF_8)))
        assertNull(parseProxyDetailsVersion("""{"specVersion":1,"version":"773"}""".toByteArray(StandardCharsets.UTF_8)))
        assertNull(parseProxyDetailsVersion("""{"specVersion":1,"version":773.5}""".toByteArray(StandardCharsets.UTF_8)))
        assertNull(parseProxyDetailsVersion("""{"specVersion":2,"version":773}""".toByteArray(StandardCharsets.UTF_8)))
        assertNull(parseProxyDetailsVersion(byteArrayOf(0xff.toByte(), 0xfe.toByte())))
    }

    @Test
    fun `detects protocol mismatch only when both protocol versions are known and differ`() {
        assertFalse(isProtocolMismatch(776, 776))
        assertFalse(isProtocolMismatch(null, 776))
        assertFalse(isProtocolMismatch(-1, 776))
        assertFalse(isProtocolMismatch(776, -1))
        assertTrue(isProtocolMismatch(773, 776))
        assertTrue(isProtocolMismatch(777, 776))
    }
}
