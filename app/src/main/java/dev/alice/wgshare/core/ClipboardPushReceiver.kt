package dev.alice.wgshare.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.alice.wgshare.service.SyncService

/**
 * Receives clipboard text pushed by the ReVanced-patched SwiftKey (see revanced/) and relays it.
 * Exported but guarded by the signature-level [ACTION]-matching permission declared in the manifest,
 * so only same-key-signed senders (our hook APK) can invoke it.
 */
class ClipboardPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        SyncService.start(context) // wake the tunnel if the app process was cold-started by this broadcast
        Repo.onExternalClipboard(text)
    }

    companion object {
        const val ACTION = "dev.alice.wgshare.action.CLIPBOARD_PUSH"
        const val EXTRA_TEXT = "text"
    }
}
