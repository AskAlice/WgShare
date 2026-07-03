package dev.alice.wgshare.xposed

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed entry point loaded into the KDE Connect Android app (`org.kde.kdeconnect_tp`).
 *
 * Registers a receiver for clipboard text pushed by WgShare and injects it straight into KDE
 * Connect's `ClipboardPlugin`, so KDE Connect emits a real `kdeconnect.clipboard` packet to every
 * paired device. This reuses KDE Connect's transport / TLS / pairing (and its existing SMS +
 * notification sync), so WgShare only has to *capture* the clip — it doesn't reimplement any sync.
 *
 * We pass the captured text directly (not via the system clipboard) because Android 10+ blocks
 * background clipboard reads even for KDE Connect. Only WgShare — holding the signature-level
 * `CLIPBOARD_PUSH` permission (i.e. signed with the same key) — can deliver to the receiver.
 */
class KdeConnectInjector : IXposedHookLoadPackage {
    private var attached = false

    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        if (lp.packageName != KDE_PACKAGE) return
        val classLoader = lp.classLoader
        XposedHelpers.findAndHookMethod(
            Application::class.java, "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (attached) return
                    attached = true
                    runCatching { register(param.thisObject as Application, classLoader) }
                        .onFailure { XposedBridge.log("WgShareKde register failed: $it") }
                }
            },
        )
    }

    private fun register(app: Application, classLoader: ClassLoader) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
                runCatching { inject(classLoader, text) }
                    .onFailure { XposedBridge.log("WgShareKde inject failed: $it") }
            }
        }
        app.registerReceiver(receiver, IntentFilter(ACTION), PERMISSION, null, Context.RECEIVER_EXPORTED)
        XposedBridge.log("WgShareKde receiver registered in ${app.packageName}")
    }

    /** Reflectively push [text] through every paired + reachable device's ClipboardPlugin. */
    private fun inject(classLoader: ClassLoader, text: String) {
        val kdeConnect = XposedHelpers.findClass(KDE_CLASS, classLoader)
        val instance = XposedHelpers.callStaticMethod(kdeConnect, "getInstance")
        val devices = (runCatching { XposedHelpers.callMethod(instance, "getDevices") }.getOrNull()
            ?: runCatching { XposedHelpers.getObjectField(instance, "devices") }.getOrNull()) as? Map<*, *>
            ?: return
        var sent = 0
        for (device in devices.values) {
            device ?: continue
            val paired = runCatching { XposedHelpers.callMethod(device, "isPaired") as Boolean }.getOrDefault(false)
            val reachable = runCatching { XposedHelpers.callMethod(device, "isReachable") as Boolean }.getOrDefault(false)
            if (!paired || !reachable) continue
            val plugin = runCatching { XposedHelpers.callMethod(device, "getPlugin", PLUGIN_KEY) }.getOrNull() ?: continue
            runCatching { XposedHelpers.callMethod(plugin, "propagateClipboard", text); sent++ }
        }
        XposedBridge.log("WgShareKde propagated clipboard to $sent device(s)")
    }

    companion object {
        private const val KDE_PACKAGE = "org.kde.kdeconnect_tp"
        private const val KDE_CLASS = "org.kde.kdeconnect.KdeConnect"
        private const val PLUGIN_KEY = "ClipboardPlugin"
        private const val ACTION = "dev.alice.wgshare.action.KDECONNECT_CLIP"
        private const val EXTRA_TEXT = "text"
        private const val PERMISSION = "dev.alice.wgshare.permission.CLIPBOARD_PUSH"
    }
}
