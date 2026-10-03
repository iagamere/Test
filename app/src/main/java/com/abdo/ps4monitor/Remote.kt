package com.abdo.ps4monitor
import java.io.IOException

/**
 * Deleting files ON THE PS4. Uses plain FTP (DELE / RMD). The ezRemote "/__local__/remove" endpoint exists but its request body is not
 * confirmed from source, so it is deliberately NOT used.
 * There is no confirmed ezRemote call that cancels a running download; deleting the partial file is the only lever this app has.
 */
object Remote {
    class Item(val path: String, val isDir: Boolean)
    class Outcome(val deleted: List<String>, val failed: List<Pair<String, String>>, val reappeared: List<String>)

    /** True when [path] is not inside the PS4's configured download folder: probably not a file the user downloaded. */
    fun outside(p: Ps4, path: String): Boolean { val base = DownloadMonitor.norm(p.dest); return !(path == base || path.startsWith("$base/")) }

    fun delete(p: Ps4, items: List<Item>, recheckDelayMs: Long = 0): Outcome {
        if (!PkgInspector.ftpOn(p)) throw IOException("Deleting files on the PS4 needs FTP. Enable the FTP port for this PS4.")
        val s = Store.settings()
        val f = Ftp.connect(Conn(p.host, p.ftpPort, p.ftpUser, p.ftpPass), s)
        val ok = ArrayList<String>(); val failed = ArrayList<Pair<String, String>>()
        try {
            for (item in items) {
                val good = try { if (item.isDir) f.removeDirectory(item.path) else f.deleteFile(item.path) } catch (e: IOException) { false }
                if (good) { ok += item.path; DownloadMonitor.d("Deleted on PS4: ${item.path}") }
                else { val why = f.replyString.orEmpty().trim().take(100).ifBlank { "refused" }; failed += item.path to why; DownloadMonitor.d("Delete refused: ${item.path} ($why)") }
            }
        } finally { runCatching { f.logout() }; runCatching { f.disconnect() } }
        if (recheckDelayMs > 0 && ok.isNotEmpty()) Thread.sleep(recheckDelayMs)
        // Evidence, not assumption: list the parent folders again and report anything that is back.
        val back = ArrayList<String>()
        ok.groupBy { it.substringBeforeLast('/', "/").ifEmpty { "/" } }.forEach { (dir, paths) ->
            val names = runCatching { PkgInspector.browse(p, dir).map { it.name }.toSet() }.getOrNull() ?: return@forEach
            paths.filter { it.substringAfterLast('/') in names }.forEach { back += it }
        }
        if (back.isNotEmpty()) DownloadMonitor.d("Still present after delete: ${back.joinToString()}")
        return Outcome(ok - back.toSet(), failed, back)
    }

    fun summary(o: Outcome): String {
        val parts = ArrayList<String>()
        if (o.deleted.isNotEmpty()) parts += tr("Deleted ${o.deleted.size} item(s) from the PS4.", "حُذف ${o.deleted.size} عنصر من الـPS4.")
        o.failed.forEach { parts += it.first.substringAfterLast('/') + ": " + it.second }
        if (o.reappeared.isNotEmpty()) parts += tr("${o.reappeared.size} item(s) are still present on the PS4.", "${o.reappeared.size} عنصر ما زال موجودًا على الـPS4.")
        return parts.joinToString("\n").ifBlank { tr("Nothing was deleted.", "لم يُحذف شيء.") }
    }
}
