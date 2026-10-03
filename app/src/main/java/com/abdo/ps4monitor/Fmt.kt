package com.abdo.ps4monitor
import java.util.Locale

object Fmt {
    fun bytes(b: Long): String {
        val u = arrayOf("B", "KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, if (i == 0) "%.0f %s" else "%.2f %s", v, u[i])
    }
    fun gb(b: Long) = String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    fun mbs(bps: Double) = String.format(Locale.US, "%.2f MB/s", bps / 1048576)
    fun mbmin(bps: Double) = String.format(Locale.US, "%.0f MB/min", bps * 60 / 1048576)
    fun mbit(bps: Double) = String.format(Locale.US, "%.1f Mbps", bps * 8 / 1_000_000)
    fun dur(sec: Long): String {
        if (sec < 0) return "Unknown"
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return when {
            h > 0 -> "$h ${tr("h","س")} $m ${tr("min","د")}"
            m > 0 -> if (m < 10 && s > 0) "$m ${tr("min","د")} $s ${tr("s","ث")}" else "$m ${tr("min","د")}"
            else -> "$s ${tr("s","ث")}"
        }
    }
    fun parseSize(t: String): Long {
        val m = Regex("""([\d.]+)\s*(KB|MB|GB|TB)?""", RegexOption.IGNORE_CASE).find(t.trim()) ?: return 0
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0
        val mul = when (m.groupValues[2].uppercase()) { "KB" -> 1L shl 10; "MB" -> 1L shl 20; "TB" -> 1L shl 40; else -> 1L shl 30 }
        return (n * mul).toLong()
    }
    fun time(ms: Long): String = java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(ms))
    fun dt(ms: Long): String = if (ms <= 0) "—" else java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(java.util.Date(ms))
}
