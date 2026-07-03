package dev.alice.wgshare.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.alice.wgshare.core.Repo
import dev.alice.wgshare.model.MessageKind
import dev.alice.wgshare.model.PairingPayload
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel : ViewModel() {
    val peers = Repo.store.peersFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val messages = Repo.store.messagesFlow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Captured/synced clipboard entries, newest first — the primary "clipboard history" view. */
    val clips = Repo.store.messagesFlow
        .map { list -> list.filter { it.kind == MessageKind.CLIPBOARD }.reversed() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val connected = Repo.connected

    fun myIp(): String? = Repo.myIp()
    suspend fun myPayload(): PairingPayload = Repo.myPairingPayload()

    fun pairWith(payload: PairingPayload) = viewModelScope.launch { Repo.pairWith(payload) }
    fun removePeer(deviceId: String) = viewModelScope.launch { Repo.store.removePeer(deviceId) }
    fun sendMessage(text: String) = viewModelScope.launch { Repo.broadcastMessage(text) }
    fun sendClipboard() = viewModelScope.launch { Repo.broadcastClipboard() }

    /** Copy a history entry back to the system clipboard (and re-share it). */
    fun copyAndShare(text: String) = viewModelScope.launch { Repo.setClipboard(text); Repo.broadcastMessage(text) }
    fun copyToClipboard(text: String) = Repo.setClipboard(text)
    fun deleteClip(id: String) = viewModelScope.launch { Repo.store.removeMessage(id) }
    fun clearHistory() = viewModelScope.launch { Repo.store.clearMessages() }
}
