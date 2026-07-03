# Keep extension entry points invoked from patched bytecode (called reflectively at runtime by the
# injected invoke-static instructions), so R8 can't strip or rename them.
-keep class app.revanced.extension.** { public *; }
