package dev.alice.wgshare.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Clipboard access. NOTE: Android 10+ only allows reading the clipboard while the app is the
 * foreground/focused app or the default IME, so [read] is invoked from a foreground UI action.
 */
object Clipboard {
    fun read(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    fun write(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WgShare", text))
    }
}
