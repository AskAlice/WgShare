package dev.alice.wgshare.xposed

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Placeholder launcher screen; the real work happens in the LSPosed-loaded [ClipboardHook]. */
class InfoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.xposed_desc)
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                textSize = 16f
            },
        )
    }
}
