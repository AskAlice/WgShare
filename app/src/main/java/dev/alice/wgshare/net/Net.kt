package dev.alice.wgshare.net

import android.util.Log
import dev.alice.wgshare.model.Envelope
import dev.alice.wgshare.model.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

const val APP_PORT = 8787
private const val TAG = "WgShare/Net"
private const val MAX_FRAME = 4 * 1024 * 1024
private val json = Json { ignoreUnknownKeys = true }

private fun Socket.writeEnvelope(env: Envelope) {
    val bytes = json.encodeToString(Envelope.serializer(), env).toByteArray()
    DataOutputStream(getOutputStream()).run { writeInt(bytes.size); write(bytes); flush() }
}

private fun Socket.readEnvelope(): Envelope {
    val input = DataInputStream(getInputStream())
    val len = input.readInt()
    require(len in 1..MAX_FRAME) { "bad frame length $len" }
    val buf = ByteArray(len).also { input.readFully(it) }
    return json.decodeFromString(Envelope.serializer(), String(buf))
}

/** Accepts one [Envelope] per connection on [APP_PORT] and forwards it to [onEnvelope]. */
class PeerServer(
    private val scope: CoroutineScope,
    private val onEnvelope: suspend (Envelope) -> Unit,
) {
    private var job: Job? = null

    /** Binds to [bindHost] (our Tailscale IP) so only tailnet peers can reach the listener. */
    fun start(bindHost: String?) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            ServerSocket().use { server ->
                server.reuseAddress = true
                val bound = runCatching { server.bind(InetSocketAddress(bindHost, APP_PORT)) }
                    .onFailure { Log.w(TAG, "bind $bindHost failed, falling back to wildcard", it) }
                if (bound.isFailure) server.bind(InetSocketAddress(APP_PORT))
                Log.i(TAG, "listening on ${server.localSocketAddress}")
                while (isActive) {
                    val client = runCatching { server.accept() }.getOrNull() ?: continue
                    launch(Dispatchers.IO) {
                        client.use {
                            runCatching { onEnvelope(it.readEnvelope()) }
                                .onFailure { e -> Log.w(TAG, "rx failed", e) }
                        }
                    }
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}

/** Sends a single [Envelope] to [peer] over the tailnet. Returns true on success. */
suspend fun sendEnvelope(peer: Peer, env: Envelope, timeoutMs: Int = 5000): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            Socket().use {
                it.connect(InetSocketAddress(peer.tsIp, APP_PORT), timeoutMs)
                it.writeEnvelope(env)
            }
            true
        }.onFailure { Log.w(TAG, "tx to ${peer.tsIp} failed", it) }.getOrDefault(false)
    }
