package dev.alice.wgshare.core

import android.content.Context
import android.content.Intent

/**
 * Hands captured clipboard text to the **local** KDE Connect app so it can sync to all its paired
 * devices (and we inherit its SMS / notification sync for free). Delivery is an explicit broadcast
 * to the [KdeConnectInjector] receiver running inside `org.kde.kdeconnect_tp`; that receiver
 * enforces our signature-level `CLIPBOARD_PUSH` permission, so only this app can push.
 */
object KdeConnect {
    const val PACKAGE = "org.kde.kdeconnect_tp"
    private const val ACTION = "dev.alice.wgshare.action.KDECONNECT_CLIP"
    private const val EXTRA_TEXT = "text"

    fun push(context: Context, text: String) {
        if (text.isBlank()) return
        runCatching {
            context.sendBroadcast(Intent(ACTION).setPackage(PACKAGE).putExtra(EXTRA_TEXT, text))
        }
    }
}
