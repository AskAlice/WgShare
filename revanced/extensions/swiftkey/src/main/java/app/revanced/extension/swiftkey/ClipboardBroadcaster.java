package app.revanced.extension.swiftkey;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

/**
 * Injected into SwiftKey by the SwiftKey clipboard-capture patch. SwiftKey is an IME, so it may
 * read the clipboard in the background (Android 10+ allows the default IME). On each copy it
 * broadcasts the text to WgShare, which records history and forwards to KDE Connect.
 */
public final class ClipboardBroadcaster {
    private static final String WG_PACKAGE = "dev.alice.wgshare";
    private static final String WG_RECEIVER = "dev.alice.wgshare.core.ClipboardPushReceiver";
    private static final String ACTION = "dev.alice.wgshare.action.CLIPBOARD_PUSH";
    private static final String EXTRA_TEXT = "text";

    private static volatile String last;
    private static boolean installed;

    private ClipboardBroadcaster() {}

    /** Called once from SwiftKey's patched startup; registers a primary-clip listener. */
    public static void install(Context context) {
        if (installed || context == null) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.addPrimaryClipChangedListener(() -> {
            try {
                if (!cm.hasPrimaryClip()) return;
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return;
                CharSequence text = clip.getItemAt(0).coerceToText(app);
                if (text != null) broadcast(app, text.toString());
            } catch (Throwable ignored) {
            }
        });
    }

    public static void broadcast(Context context, String text) {
        if (text == null || text.isEmpty() || text.equals(last)) return;
        last = text;
        try {
            Intent intent = new Intent(ACTION)
                .setClassName(WG_PACKAGE, WG_RECEIVER)
                .putExtra(EXTRA_TEXT, text);
            context.getApplicationContext().sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }
}
