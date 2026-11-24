package gg.nekohosting.vanilla.jreiproxyserver.network

import com.google.gson.JsonParser
import gg.nekohosting.vanilla.jreiproxyserver.JReiProxyServer
import net.minecraft.SharedConstants
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks each player's Minecraft protocol version across direct and proxied connections.
 *
 * When ViaVersion runs on a proxy (such as Velocity) rather than the backend, Paper sees the
 * proxy's translated connection and reports the backend's own protocol version. ViaVersion's
 * `send-player-details` message (`vv:proxy_details`) carries the player's true native protocol
 * version; backend ViaVersion (when installed) and Paper's `Player.protocolVersion` serve as
 * fallbacks for direct or backend-translated setups.
 */
class ClientVersionTracker(private val plugin: JReiProxyServer) : PluginMessageListener {

    private val proxyVersions = ConcurrentHashMap<UUID, Int>()

    @Volatile
    private var viaLookupAttempted = false

    @Volatile
    private var viaLookup: ViaReflection? = null

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != Channels.ViaVersion.PROXY_DETAILS) return
        val version = parseProxyDetailsVersion(message) ?: return
        proxyVersions[player.uniqueId] = version
    }

    fun onPlayerQuit(player: Player) {
        proxyVersions.remove(player.uniqueId)
    }

    fun serverProtocolVersion(): Int = SharedConstants.getProtocolVersion()

    fun resolveProtocolVersion(player: Player): Int? {
        proxyVersions[player.uniqueId]?.takeIf { it > 0 }?.let { return it }
        resolveViaVersionApi(player.uniqueId)?.let { return it }
        return player.protocolVersion.takeIf { it > 0 }
    }

    private fun resolveViaVersionApi(playerId: UUID): Int? {
        val viaPlugin = plugin.server.pluginManager.getPlugin("ViaVersion") ?: return null
        if (!viaPlugin.isEnabled) return null
        return try {
            val lookup = ensureViaLookup(viaPlugin.javaClass.classLoader) ?: return null
            val api = lookup.getApi.invoke(null) ?: return null
            val version = lookup.getPlayerVersion.invoke(api, playerId) as? Int ?: return null
            version.takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureViaLookup(loader: ClassLoader): ViaReflection? {
        if (viaLookupAttempted) return viaLookup
        synchronized(this) {
            if (viaLookupAttempted) return viaLookup
            viaLookup = try {
                val viaClass = Class.forName("com.viaversion.viaversion.api.Via", true, loader)
                val viaApiClass = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, loader)
                ViaReflection(
                    getApi = viaClass.getMethod("getAPI"),
                    getPlayerVersion = viaApiClass.getMethod("getPlayerVersion", UUID::class.java),
                )
            } catch (_: Throwable) {
                null
            } finally {
                viaLookupAttempted = true
            }
            return viaLookup
        }
    }

    private class ViaReflection(
        val getApi: Method,
        val getPlayerVersion: Method,
    )
}

/**
 * Parses the player's native Minecraft protocol version from a `vv:proxy_details` JSON payload.
 *
 * Accepts specVersion 1 payloads with a positive integer `version`; malformed or unsupported
 * payloads are ignored safely without throwing.
 */
internal fun parseProxyDetailsVersion(bytes: ByteArray): Int? {
    if (bytes.isEmpty()) return null
    return try {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        val element = JsonParser.parseString(text)
        if (!element.isJsonObject) return null

        val obj = element.asJsonObject
        val specPrimitive = obj.get("specVersion")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!specPrimitive.isNumber || specPrimitive.asBigDecimal.intValueExact() != 1) return null

        val versionPrimitive = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!versionPrimitive.isNumber) return null
        val version = versionPrimitive.asBigDecimal.intValueExact()
        version.takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }
}

/** Returns true only when both protocol versions are known and differ. */
internal fun isProtocolMismatch(clientProtocol: Int?, serverProtocol: Int): Boolean =
    clientProtocol != null && clientProtocol > 0 && serverProtocol > 0 && clientProtocol != serverProtocol
