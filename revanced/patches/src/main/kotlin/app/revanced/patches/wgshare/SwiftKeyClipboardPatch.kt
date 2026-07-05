package app.revanced.patches.wgshare

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.bytecodePatch

/**
 * Replaces the old LSPosed [ClipboardHook]: instead of hooking SwiftKey at runtime, this statically
 * injects a call into SwiftKey's startup so it registers a clipboard listener that broadcasts every
 * copied text to WgShare (`CLIPBOARD_PUSH`). Because revanced-cli re-signs SwiftKey with our key,
 * the broadcast satisfies the signature-level permission on WgShare's receiver.
 *
 * The injected code lives in the `extensions/swiftkey` extension
 * (`app.revanced.extension.swiftkey.ClipboardBroadcaster`).
 */
internal val swiftKeyOnCreateFingerprint = fingerprint {
    returns("V")
    // Verified against SwiftKey (com.touchtype.swiftkey): the manifest-declared IME service
    // com.touchtype.KeyboardService declares `public onCreate()V`. Unobfuscated + stable anchor.
    custom { method, classDef ->
        method.name == "onCreate" && classDef.type == "Lcom/touchtype/KeyboardService;"
    }
}

val swiftKeyClipboardPatch = bytecodePatch(
    name = "SwiftKey clipboard capture",
    description = "Broadcast copied text to WgShare for clipboard history + KDE Connect sync.",
) {
    compatibleWith("com.touchtype.swiftkey")
    extendWith("extensions/swiftkey.rve")

    execute {
        // `p0` in onCreate is the service instance (InputMethodService -> Service -> Context).
        swiftKeyOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, " +
                "Lapp/revanced/extension/swiftkey/ClipboardBroadcaster;->install(Landroid/content/Context;)V",
        )
    }
}
