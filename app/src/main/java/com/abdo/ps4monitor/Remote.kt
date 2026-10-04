package com.abdo.ps4monitor
import java.io.IOException

/**
 * Deleting files ON THE PS4.
 * Primary: ezRemote POST /__local__/remove {"items":[...]} (confirmed in http_server.cpp; recursive, so folders go with their content).
 * Fallback: FTP DELE / RMD (folders only if empty). Every result is verified by listing the parent folder again.
 * There is still no confirmed call that cancels a running transfer. (GET /stop in that file stops the whole ezRemote web server: never used.)
 */
object Remote {
    class Item(val path: String, val isDir: Boolean)
    class Outcome(val deleted: List<String>, val failed: List<Pair<String, String>>, val reappeared: List<String>)

    /** True when [path] is not inside the PS4's configured download folder: probably not a file the user downloaded. */
    fun outside(p: Ps4, path: String): Boolean { val base = DownloadMonitor.norm(p.dest); return !(path == base || path.startsWith("$base/")) }

    private fun presentAfter(p: Ps4, paths: List<String>): Set<String>? {
        val out = HashSet<String>()
        for ((dir, ps) in paths.groupBy { it.substringBeforeLast('/', "/").ifEmpty { "/" } }) {
            val names = try { PkgInspector.browse(p, dir).map { it.name }.toSet() } catch (e: Exception) { return null }
            ps.filter { it.substringAfterLast('/') in names }.forEach { out += it }
        }
        return out
    }

    fun delete(p: Ps4, items: List<Item>, recheckDelayMs: Long = 0): Outcome {
        if (!PkgInspector.canRead(p)) throw IOException("No web or FTP connection is enabled for this PS4.")
        val paths = items.map { it.path }.distinct()
        if (paths.any { it.trim('/').split('/').filter { s -> s.isNotEmpty() }.size < 2 }) throw IOException("Refusing to delete a top-level system folder.")
        val s = Store.settings()
        var why = ""; var httpOk = false
        if (PkgInspector.httpOn(p)) {
            try { val r = EzRemote.remove(p, paths, s.timeout * 1000); httpOk = r.ok; if (!r.ok) why = r.message
                DownloadMonitor.d("ezRemote remove (${paths.size}): ${if (r.ok) "ok" else r.message}") }
            catch (e: Exception) { why = EzRemote.friendly(e); DownloadMonitor.d("ezRemote remove error: ${e.javaClass.simpleName}: ${e.message}") }
        }
        var left = presentAfter(p, paths)                       // null = could not verify
        val todo = left ?: paths.toSet()
        if (PkgInspector.ftpOn(p) && (todo.isNotEmpty() && (left != null || !httpOk))) {
            val f = try { Ftp.connect(Conn(p.host, p.ftpPort, p.ftpUser, p.ftpPass), s) } catch (e: Exception) { null }
            if (f != null) try {
                for (item in items.filter { it.path in todo }) {
                    val good = try { if (item.isDir) f.removeDirectory(item.path) else f.deleteFile(item.path) } catch (e: IOException) { false }
                    if (good) DownloadMonitor.d("Deleted on PS4 (FTP): ${item.path}") else { why = f.replyString.orEmpty().trim().take(100).ifBlank { why }; DownloadMonitor.d("FTP delete refused: ${item.path} ($why)") }
                }
            } finally { runCatching { f.logout() }; runCatching { f.disconnect() } }
            left = presentAfter(p, paths)
        }
        val stillThere: Set<String> = left ?: if (httpOk) emptySet() else paths.toSet()     // unverifiable: trust the answer
        var back = emptyList<String>()
        if (recheckDelayMs > 0 && stillThere.size < paths.size) {
            Thread.sleep(recheckDelayMs)
            val again = presentAfter(p, paths)
            if (again != null) back = (again - stillThere).toList()
        }
        if (back.isNotEmpty()) DownloadMonitor.d("Reappeared after delete: ${back.joinToString()}")
        return Outcome(paths.filter { it !in stillThere && it !in back }, stillThere.map { it to why.ifBlank { "still present" } }, back)
    }

    fun summary(o: Outcome): String {
        val parts = ArrayList<String>()
        if (o.deleted.isNotEmpty()) parts += tr("Deleted ${o.deleted.size} item(s) from the PS4.", "حُذف ${o.deleted.size} عنصر من الـPS4.")
        o.failed.forEach { parts += it.first.substringAfterLast('/') + ": " + Tx.t(it.second) }
        if (o.reappeared.isNotEmpty()) parts += tr("${o.reappeared.size} item(s) are still present on the PS4.", "${o.reappeared.size} عنصر ما زال موجودًا على الـPS4.")
        return parts.joinToString("\n").ifBlank { tr("Nothing was deleted.", "لم يُحذف شيء.") }
    }
}
