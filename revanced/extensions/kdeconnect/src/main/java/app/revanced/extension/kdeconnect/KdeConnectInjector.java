package app.revanced.extension.kdeconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Injected into KDE Connect by the KDE Connect clipboard-inject patch. Registers a receiver for
 * WgShare's push and feeds the text into KDE Connect's ClipboardPlugin, so KDE Connect syncs it to
 * every paired device (reusing its transport, TLS, pairing, SMS + notification sync).
 *
 * Runs inside the KDE Connect process, so it calls KDE Connect internals reflectively (they exist
 * at runtime). The receiver is guarded by WgShare's signature-level permission, so only WgShare
 * (same signing key, via revanced-cli) can push.
 */
public final class KdeConnectInjector {
    private static final String ACTION = "dev.alice.wgshare.action.KDECONNECT_CLIP";
    private static final String EXTRA_TEXT = "text";
    private static final String PERMISSION = "dev.alice.wgshare.permission.CLIPBOARD_PUSH";

    private static boolean installed;

    private KdeConnectInjector() {}

    /** Called once from KDE Connect's patched startup. */
    public static void install(Context context) {
        if (installed || context == null) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (text != null && !text.isEmpty()) inject(text);
            }
        };
        final IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter, PERMISSION, null);
        }
    }

    /** Push [text] through every paired + reachable device's ClipboardPlugin. */
    private static void inject(String text) {
        try {
            Class<?> kdeConnect = Class.forName("org.kde.kdeconnect.KdeConnect");
            Object instance = kdeConnect.getMethod("getInstance").invoke(null);
            Object devicesObj = kdeConnect.getMethod("getDevices").invoke(instance);
            if (!(devicesObj instanceof Map)) return;
            for (Object device : ((Map<?, ?>) devicesObj).values()) {
                if (device == null) continue;
                try {
                    Class<?> dc = device.getClass();
                    if (!(Boolean) dc.getMethod("isPaired").invoke(device)) continue;
                    if (!(Boolean) dc.getMethod("isReachable").invoke(device)) continue;
                    Object plugin = dc.getMethod("getPlugin", String.class).invoke(device, "ClipboardPlugin");
                    if (plugin == null) continue;
                    Method propagate = plugin.getClass().getDeclaredMethod("propagateClipboard", String.class);
                    propagate.setAccessible(true);
                    propagate.invoke(plugin, text);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
