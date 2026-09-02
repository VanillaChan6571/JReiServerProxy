package gg.nekohosting.vanilla.jreiproxyserver.nms

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.CraftServer
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player

internal val Player.nms: ServerPlayer
    get() = (this as CraftPlayer).handle

internal val minecraftServer: MinecraftServer
    get() = (Bukkit.getServer() as CraftServer).server

internal fun RegistryAccess.newBuf(): RegistryFriendlyByteBuf =
    RegistryFriendlyByteBuf(Unpooled.buffer(), this)

internal fun RegistryAccess.readBuf(bytes: ByteArray): RegistryFriendlyByteBuf =
    RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), this)

internal fun ServerPlayer.sendPacket(packet: Packet<*>) {
    connection.send(packet)
}

/**
 * Sends raw bytes on a channel this server has no codec for.
 *
 * Bukkit's plugin messenger caps a single message at 32 KB, which the recipe payload passes by
 * orders of magnitude, so recipes leave as a plain vanilla custom-payload packet instead.
 */
internal fun ServerPlayer.sendRawPayload(channel: String, bytes: ByteArray) {
    sendPacket(ClientboundCustomPayloadPacket(DiscardedPayload(Identifier.parse(channel), bytes)))
}

/** Copies the written bytes out without consuming the buffer. */
internal fun RegistryFriendlyByteBuf.copyBytes(): ByteArray {
    val out = ByteArray(readableBytes())
    getBytes(readerIndex(), out)
    return out
}
