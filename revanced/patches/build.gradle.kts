version = (project.findProperty("verName") as String?)?.ifBlank { null } ?: "1.0.0"

patches {
    about {
        name = "WgShare Patches"
        description = "Clipboard capture (SwiftKey) and clipboard injection (KDE Connect) for WgShare."
        source = "https://github.com/alice/wgshare"
        author = "WgShare"
        contact = "alice@askalice.me"
        website = "https://github.com/alice/wgshare"
        license = "GPL-3.0"
    }
}
