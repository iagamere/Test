package com.abdo.ps4monitor
import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this); PkgStore.init(this); DownloadMonitor.init(this); Notifier.init(this)
        DownloadMonitor.restore()       // process restarted: reload records, re-attach monitoring; never re-sends a download request
    }
}
