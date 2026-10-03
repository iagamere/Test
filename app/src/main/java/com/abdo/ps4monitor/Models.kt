package com.abdo.ps4monitor

import java.util.UUID

enum class MonitorMode { AUTO, HTTP_ONLY, FTP_ONLY }

/** One PS4 console. ftpPort = 0 disables FTP for this console. */
data class Ps4(
    val id: String = UUID.randomUUID().toString(),
    val name: String, val host: String,
    val httpPort: Int = 8080, val ftpPort: Int = 2121,
    val ftpUser: String = "", val ftpPass: String = "",
    val dest: String = "/data/pkg", val mode: MonitorMode = MonitorMode.AUTO
)

/** Separate concepts, never merged: request state / http / ftp / reachability / download state. */
enum class Link(private val en: String, private val ar: String) {
    UNKNOWN("Not checked", "لم يُفحص"), AVAILABLE("Available", "متاح"), UNAVAILABLE("Unavailable", "غير متاح"),
    RECONNECTING("Reconnecting…", "إعادة الاتصال…"), STANDBY("Standby (fallback)", "احتياطي"), DISABLED("Off", "متوقف");
    val label get() = tr(en, ar)
}
enum class Reach { UNKNOWN, REACHABLE, UNREACHABLE }
data class Ps4Status(
    val http: Link = Link.UNKNOWN, val ftp: Link = Link.UNKNOWN, val reach: Reach = Reach.UNKNOWN,
    val lastOkAt: Long = 0, val message: String = ""
)

enum class DlState(private val en: String, private val ar: String, val active: Boolean) {
    SUBMITTING("Sending to PS4", "جارٍ الإرسال إلى الـPS4", true),
    QUEUED("Queued", "في قائمة الانتظار", true),
    WAITING_FOR_START("Waiting for PS4 to start", "بانتظار بدء الـPS4", true),
    STARTING("Starting", "جارٍ البدء", true),
    DOWNLOADING("Downloading", "جارٍ التحميل", true),
    STALLED("Stalled", "متعثّر", true),
    CONNECTION_LOST("Connection lost", "انقطع الاتصال", true),
    VERIFYING("Verifying", "جارٍ التحقق", true),
    COMPLETED("Completed", "اكتمل", false),
    FAILED("Failed", "فشل", false),
    STOPPED("Monitoring stopped", "أُوقفت المراقبة", false),
    NOT_STARTED("Download not started", "لم يبدأ التحميل", false);
    val label get() = tr(en, ar)
}

data class Download(
    val id: String, val ps4Id: String, val attempt: Int = 1, val retryOf: String? = null,
    val sourceUrl: String, val displayName: String, val dest: String,
    val tempPath: String? = null, val finalPath: String? = null,
    val expectedSize: Long? = null, val expectedSource: String = "",
    val currentSize: Long = 0, val speed: Double = 0.0, val avgSpeed: Double = 0.0, val peakSpeed: Double = 0.0,
    val etaSec: Long = -1,
    val state: DlState = DlState.SUBMITTING, val note: String = "",
    val createdAt: Long, val submittedAt: Long = 0, val startedAt: Long = 0, val completedAt: Long = 0,
    val lastSeenAt: Long = 0, val errorMessage: String? = null, val notificationId: Int,
    /** Files (name -> size) that existed in dest before submission; null = snapshot failed. */
    val baseline: Map<String, Long>? = null,
    val superseded: Boolean = false, val terminalNotified: Boolean = false,
    val updatedAt: Long = createdAt,
    val pkgTitle: String? = null, val titleId: String? = null, val iconReady: Boolean = false,   // read from the PKG itself (see Pkg.kt)
    val fileName: String? = null,                  // name chosen in the app (sent to ezRemote when "send name" is on)
    val speeds: List<Float> = emptyList()          // runtime only, not persisted
) {
    val pct: Int? get() = expectedSize?.takeIf { it > 0 }?.let { (currentSize * 100 / it).toInt().coerceIn(0, 100) }
    val frac: Float? get() = expectedSize?.takeIf { it > 0 }?.let { (currentSize.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

data class FsEntry(val name: String, val size: Long, val mtime: Long = 0, val isDir: Boolean = false)

sealed class SubmitResult {
    data class Accepted(val id: String, val note: String = "") : SubmitResult()
    data class Rejected(val message: String) : SubmitResult()
    data class Unreachable(val message: String) : SubmitResult()
    data class Duplicate(val message: String) : SubmitResult()
    data class Invalid(val message: String) : SubmitResult()
}
