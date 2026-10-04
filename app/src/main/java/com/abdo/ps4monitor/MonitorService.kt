package com.abdo.ps4monitor
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*

/** Keeps the process alive and hosts monitoring. All state lives in DownloadMonitor / DownloadRepo, not in the UI. */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wl: PowerManager.WakeLock? = null
    private var wf: WifiManager.WifiLock? = null
    private var cm: ConnectivityManager? = null
    private val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { DownloadMonitor.net.value = true; DownloadMonitor.kick() }
        override fun onLost(n: Network) { DownloadMonitor.net.value = false }
    }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        DownloadMonitor.restore()                 // idempotent; re-attaches after Android restarted us
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.init(this)
        ServiceCompat.startForeground(this, 1, Notifier.summary(this, tr("Starting…", "جارٍ البدء…")),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ps4monitor:poll").apply { acquire() }
        @Suppress("DEPRECATION")
        wf = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ps4monitor").apply { acquire() }
        cm = getSystemService(ConnectivityManager::class.java)
        DownloadMonitor.net.value = cm?.activeNetwork != null
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        var idleSince = 0L; var lastText = ""
        scope.launch {
            // Re-evaluate every few seconds: summary text + stop when nothing needs watching.
            while (isActive) {
                val all = DownloadRepo.all.value
                val n = all.count { DownloadMonitor.isWatch(it) && it.state != DlState.NOT_STARTED }
                val waiting = all.count { it.state == DlState.NOT_STARTED && DownloadMonitor.isWatch(it) }
                val opsOn = Ops.active.value > 0
                if (n == 0 && waiting == 0 && !opsOn) {
                    if (idleSince == 0L) idleSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - idleSince > 5000) { stopSelf(); return@launch }   // grace period for a download submitted right now
                } else idleSince = 0L
                val text = if (opsOn) (Ops.label.value + (Ops.uploadPct.value.takeIf { it >= 0 }?.let { " $it%" } ?: "")) else if (n > 0) tr("Monitoring $n download${if (n > 1) "s" else ""}", "مراقبة $n تحميل") else tr("Watching for a late start", "مراقبة بدء متأخر")
                if (text != lastText) { lastText = text; Notifier.updateSummary(this@MonitorService, text) }
                delay(3000)
            }
        }
    }
    override fun onDestroy() {
        scope.cancel(); runCatching { cm?.unregisterNetworkCallback(cb) }
        runCatching { wl?.release() }; runCatching { wf?.release() }
        super.onDestroy()
    }
}
