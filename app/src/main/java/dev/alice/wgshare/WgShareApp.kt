package dev.alice.wgshare

import android.app.Application
import dev.alice.wgshare.core.Repo

class WgShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}
