package dev.alice.wgshare.core

import android.content.Context
import dev.alice.wgshare.clipboard.Clipboard
import dev.alice.wgshare.data.Store
import dev.alice.wgshare.model.Envelope
import dev.alice.wgshare.model.Message
import dev.alice.wgshare.model.MessageKind
import dev.alice.wgshare.model.PairingPayload
import dev.alice.wgshare.net.PeerServer
import dev.alice.wgshare.net.Tailscale
import dev.alice.wgshare.net.sendEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** App-wide singleton wiring identity, the Tailscale link and the peer message pipeline. */
object Repo {
    lateinit var store: Store
        private set
    private lateinit var appContext: Context
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = PeerServer(scope, ::onEnvelope)

    /** True while a Tailscale IP is present (i.e. the tailnet is reachable). */
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        store = Store(appContext)
    }

    /** Ensures identity, notes Tailscale reachability, and binds the peer listener to our tailnet IP. */
    suspend fun start() {
        store.ensureIdentity()
        val ip = Tailscale.localIp()
        _connected.value = ip != null
        server.start(ip)
    }

    fun stop() {
        server.stop()
        _connected.value = false
    }

    fun myIp(): String? = Tailscale.localIp()

    /** Builds the QR payload describing this device for the peer to scan. */
    suspend fun myPairingPayload(): PairingPayload {
        val id = store.ensureIdentity()
        return PairingPayload(name = id.name, deviceId = id.deviceId, tsIp = Tailscale.localIp() ?: "")
    }

    /**
     * Registers a scanned peer and (best-effort) tells it to add us back over the tailnet. Because
     * Tailscale gives full node-to-node connectivity, a single scan on one device is enough — no
     * per-peer allow-list dance like raw WireGuard.
     */
    suspend fun pairWith(payload: PairingPayload) {
        val peer = payload.toPeer()
        store.upsertPeer(peer)
        sendEnvelope(peer, Envelope(Envelope.Type.PAIR, store.ensureIdentity().deviceId, pairing = myPairingPayload()))
    }

    suspend fun broadcastMessage(text: String) = broadcast(Envelope.Type.MESSAGE, text, MessageKind.MESSAGE)

    suspend fun broadcastClipboard() {
        val text = Clipboard.read(appContext) ?: return
        broadcast(Envelope.Type.CLIPBOARD, text, MessageKind.CLIPBOARD)
    }

    /**
     * Entry point for clipboard text captured out-of-process (the SwiftKey LSPosed hook), which
     * sidesteps Android 10+'s foreground-only clipboard read restriction. Ensures the listener is up,
     * then relays the text to peers.
     */
    fun onExternalClipboard(text: String) {
        if (text.isBlank()) return
        scope.launch {
            runCatching {
                if (!_connected.value) start()
                broadcast(Envelope.Type.CLIPBOARD, text, MessageKind.CLIPBOARD)
            }
        }
    }

    fun setClipboard(text: String) = Clipboard.write(appContext, text)

    private suspend fun broadcast(type: Envelope.Type, text: String, kind: MessageKind) {
        val id = store.ensureIdentity()
        // Primary path: hand clipboard to the local KDE Connect app, which syncs it to its peers.
        if (kind == MessageKind.CLIPBOARD) KdeConnect.push(appContext, text)
        // Fallback path: our own Tailscale peer transport.
        val env = Envelope(type, id.deviceId, text)
        store.peers().forEach { sendEnvelope(it, env) }
        store.addMessage(message("broadcast", true, kind, text))
    }

    private suspend fun onEnvelope(env: Envelope) {
        val known = store.peers().any { it.deviceId == env.senderId }
        when (env.type) {
            Envelope.Type.PAIR -> env.pairing?.let { store.upsertPeer(it.toPeer()) }
            Envelope.Type.MESSAGE -> if (known)
                store.addMessage(message(env.senderId, false, MessageKind.MESSAGE, env.text))
            Envelope.Type.CLIPBOARD -> if (known) {
                Clipboard.write(appContext, env.text)
                store.addMessage(message(env.senderId, false, MessageKind.CLIPBOARD, env.text))
            }
        }
    }

    private fun message(peerId: String, fromMe: Boolean, kind: MessageKind, text: String) =
        Message(UUID.randomUUID().toString(), peerId, fromMe, kind, text, System.currentTimeMillis())
}
