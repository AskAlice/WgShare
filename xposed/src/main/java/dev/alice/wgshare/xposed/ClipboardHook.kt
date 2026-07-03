package dev.alice.wgshare.xposed

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed entry point, loaded into SwiftKey's process (a default IME may read the clipboard in the
 * background). Captures every copied text and forwards it to WgShare via an explicit broadcast;
 * WgShare then relays it to peers over WireGuard.
 *
 * Two independent capture paths feed a single de-duplicated [push]:
 *  1. [hookClipHistory] — hooks SwiftKey's internal "add clip to history" method. Precise (fires
 *     exactly when SwiftKey records a clip, honouring its incognito / clipboard-enabled gating) but
 *     tied to obfuscated names. Verified against **SwiftKey 9.13.10.6** (`w90.q#a(w90.x, nz.o0)`,
 *     text = field `w90.x#a`). Best-effort: if a future build renames these, it silently no-ops.
 *  2. [registerClipListener] — a plain [ClipboardManager.OnPrimaryClipChangedListener]. Version
 *     proof (public API), always registered, so sync keeps working even when path 1 breaks.
 */
class ClipboardHook : IXposedHookLoadPackage {

    private var attached = false
    private var appRef: Application? = null
    /** Last text pushed; drops the echo when a synced clip is written back to the clipboard. */
    @Volatile private var lastPush: String? = null

    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        if (lp.packageName !in TARGET_PACKAGES) return
        XposedHelpers.findAndHookMethod(
            Application::class.java, "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (attached) return
                    attached = true
                    appRef = param.thisObject as Application
                    runCatching { registerClipListener(appRef!!) }
                        .onFailure { XposedBridge.log("WgShareHook listener failed: $it") }
                }
            },
        )
        hookClipHistory(lp)
    }

    /** Path 1: intercept SwiftKey's internal clip-history insertion. */
    private fun hookClipHistory(lp: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val itemClass = XposedHelpers.findClass(CLIP_ITEM_CLASS, lp.classLoader)
            val originClass = XposedHelpers.findClass(CLIP_ORIGIN_CLASS, lp.classLoader)
            XposedHelpers.findAndHookMethod(
                CLIP_STORE_CLASS, lp.classLoader, CLIP_ADD_METHOD, itemClass, originClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val text = runCatching {
                            XposedHelpers.getObjectField(param.args[0], CLIP_TEXT_FIELD) as? String
                        }.getOrNull()
                        appRef?.let { push(it, text) }
                    }
                },
            )
            XposedBridge.log("WgShareHook clip-history hook installed ($CLIP_STORE_CLASS#$CLIP_ADD_METHOD)")
        }.onFailure {
            XposedBridge.log("WgShareHook clip-history hook unavailable (SwiftKey updated?), using listener only: $it")
        }
    }

    /** Path 2: public clipboard listener (version-proof fallback). */
    private fun registerClipListener(app: Application) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener {
            val text = cm.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(app)
                ?.toString()
            push(app, text)
        }
        XposedBridge.log("WgShareHook attached to ${app.packageName}")
    }

    private fun push(app: Application, text: String?) {
        if (text.isNullOrBlank() || text == lastPush) return
        lastPush = text
        runCatching {
            app.sendBroadcast(
                Intent(ACTION).apply {
                    setClassName(WG_PACKAGE, WG_RECEIVER)
                    putExtra(EXTRA_TEXT, text)
                },
            )
        }.onFailure { XposedBridge.log("WgShareHook broadcast failed: $it") }
    }

    companion object {
        private val TARGET_PACKAGES = setOf("com.touchtype.swiftkey", "com.touchtype.swiftkey.beta")

        // SwiftKey 9.13.10.6 obfuscated symbols (path 1). See class KDoc.
        private const val CLIP_STORE_CLASS = "w90.q"        // clipboard history store
        private const val CLIP_ADD_METHOD = "a"             // add(itemToAdd, source)
        private const val CLIP_ITEM_CLASS = "w90.x"         // LocalClipboardItem
        private const val CLIP_ORIGIN_CLASS = "nz.o0"       // add-source enum
        private const val CLIP_TEXT_FIELD = "a"             // LocalClipboardItem.text

        private const val WG_PACKAGE = "dev.alice.wgshare"
        private const val WG_RECEIVER = "dev.alice.wgshare.core.ClipboardPushReceiver"
        private const val ACTION = "dev.alice.wgshare.action.CLIPBOARD_PUSH"
        private const val EXTRA_TEXT = "text"
    }
}
