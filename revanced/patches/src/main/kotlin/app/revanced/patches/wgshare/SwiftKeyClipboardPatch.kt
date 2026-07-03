package app.revanced.patches.wgshare

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
val swiftKeyClipboardPatch = bytecodePatch(
    name = "SwiftKey clipboard capture",
    description = "Broadcast copied text to WgShare for clipboard history + KDE Connect sync.",
) {
    compatibleWith("com.touchtype.swiftkey")
    extendWith("extensions/swiftkey.rve")

    apply {
        // TODO(verify): confirm against the decompiled SwiftKey APK. The manifest-declared IME
        // service class name is the most stable anchor (obfuscation rarely renames it); adjust the
        // suffix/type if a build differs. `p0` in onCreate is the service (a Context).
        val onCreate = firstMethod {
            name("onCreate")
            custom { _, classDef -> classDef.type.endsWith("KeyboardService;") }
        }
        onCreate.addInstructions(
            0,
            "invoke-static { p0 }, " +
                "Lapp/revanced/extension/swiftkey/ClipboardBroadcaster;->install(Landroid/content/Context;)V",
        )
    }
}
