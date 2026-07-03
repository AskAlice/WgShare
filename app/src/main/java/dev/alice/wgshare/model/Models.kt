package dev.alice.wgshare.model

import kotlinx.serialization.Serializable

/**
 * This device's stable identity. Transport crypto + NAT traversal are handled by Tailscale, so we
 * only need a stable id to distinguish peers at the app layer. [tsIp] is the Tailscale (CGNAT
 * 100.64.0.0/10) address, discovered at runtime and cached for the QR payload.
 */
@Serializable
data class Identity(
    val deviceId: String,
    val name: String,
)

/** A paired remote device, addressed by its Tailscale IP. */
@Serializable
data class Peer(
    val name: String,
    val deviceId: String,
    val tsIp: String,
)

/** Data exchanged during pairing (encoded into a QR code). */
@Serializable
data class PairingPayload(
    val name: String,
    val deviceId: String,
    val tsIp: String,
) {
    fun toPeer() = Peer(name, deviceId, tsIp)
}

enum class MessageKind { MESSAGE, CLIPBOARD }

@Serializable
data class Message(
    val id: String,
    val peerId: String,
    val fromMe: Boolean,
    val kind: MessageKind,
    val text: String,
    val timestamp: Long,
)

/** Wire format sent over the Tailscale link between peers. */
@Serializable
data class Envelope(
    val type: Type,
    val senderId: String,
    val text: String = "",
    val pairing: PairingPayload? = null,
) {
    enum class Type { MESSAGE, CLIPBOARD, PAIR }
}
