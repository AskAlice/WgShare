package dev.alice.wgshare.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.alice.wgshare.model.Identity
import dev.alice.wgshare.model.Message
import dev.alice.wgshare.model.Peer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore("wgshare")

/** Persists identity, paired peers and message history in DataStore (JSON-encoded). */
class Store(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val kIdentity = stringPreferencesKey("identity")
    private val kPeers = stringPreferencesKey("peers")
    private val kMessages = stringPreferencesKey("messages")
    private val peerList = ListSerializer(Peer.serializer())
    private val msgList = ListSerializer(Message.serializer())

    val identityFlow: Flow<Identity?> = context.dataStore.data.map { p ->
        p[kIdentity]?.let { json.decodeFromString(Identity.serializer(), it) }
    }
    val peersFlow: Flow<List<Peer>> = context.dataStore.data.map { p ->
        p[kPeers]?.let { json.decodeFromString(peerList, it) } ?: emptyList()
    }
    val messagesFlow: Flow<List<Message>> = context.dataStore.data.map { p ->
        p[kMessages]?.let { json.decodeFromString(msgList, it) } ?: emptyList()
    }

    /** Returns the existing identity or mints a stable device id + name. */
    suspend fun ensureIdentity(): Identity {
        identityFlow.first()?.let { return it }
        val id = Identity(deviceId = UUID.randomUUID().toString(), name = Build.MODEL ?: "WgShare device")
        context.dataStore.edit { it[kIdentity] = json.encodeToString(Identity.serializer(), id) }
        return id
    }

    suspend fun upsertPeer(peer: Peer) = context.dataStore.edit { p ->
        val current = p[kPeers]?.let { json.decodeFromString(peerList, it) } ?: emptyList()
        p[kPeers] = json.encodeToString(peerList, current.filter { it.deviceId != peer.deviceId } + peer)
    }

    suspend fun removePeer(deviceId: String) = context.dataStore.edit { p ->
        val current = p[kPeers]?.let { json.decodeFromString(peerList, it) } ?: emptyList()
        p[kPeers] = json.encodeToString(peerList, current.filter { it.deviceId != deviceId })
    }

    suspend fun addMessage(message: Message) = context.dataStore.edit { p ->
        val current = p[kMessages]?.let { json.decodeFromString(msgList, it) } ?: emptyList()
        p[kMessages] = json.encodeToString(msgList, (current + message).takeLast(500))
    }

    suspend fun removeMessage(id: String) = context.dataStore.edit { p ->
        val current = p[kMessages]?.let { json.decodeFromString(msgList, it) } ?: emptyList()
        p[kMessages] = json.encodeToString(msgList, current.filter { it.id != id })
    }

    suspend fun clearMessages() = context.dataStore.edit { it.remove(kMessages) }

    suspend fun peers(): List<Peer> = peersFlow.first()
    suspend fun identity(): Identity? = identityFlow.first()
}
