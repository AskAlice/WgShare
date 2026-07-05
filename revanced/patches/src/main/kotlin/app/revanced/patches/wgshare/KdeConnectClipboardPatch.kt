package app.revanced.patches.wgshare

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.bytecodePatch

/**
 * Replaces the old LSPosed [KdeConnectInjector]: statically injects a call into KDE Connect's
 * startup so it registers a receiver for WgShare's `KDECONNECT_CLIP` broadcast and pushes the text
 * into `ClipboardPlugin.propagateClipboard`, letting KDE Connect sync it to all paired devices.
 *
 * KDE Connect is open-source and unobfuscated: its Application subclass is
 * `org.kde.kdeconnect.KdeConnect` (the same class as the `getInstance()` singleton), so the anchor
 * is reliable. Injected code lives in `extensions/kdeconnect`
 * (`app.revanced.extension.kdeconnect.KdeConnectInjector`).
 */
internal val kdeConnectOnCreateFingerprint = fingerprint {
    returns("V")
    custom { method, classDef ->
        method.name == "onCreate" && classDef.type == "Lorg/kde/kdeconnect/KdeConnect;"
    }
}

val kdeConnectClipboardPatch = bytecodePatch(
    name = "KDE Connect clipboard inject",
    description = "Let WgShare push captured clipboard text into KDE Connect for device sync.",
) {
    compatibleWith("org.kde.kdeconnect_tp")
    extendWith("extensions/kdeconnect.rve")

    execute {
        // `p0` in onCreate is the KdeConnect application instance (Application -> Context).
        kdeConnectOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, " +
                "Lapp/revanced/extension/kdeconnect/KdeConnectInjector;->install(Landroid/content/Context;)V",
        )
    }
}
