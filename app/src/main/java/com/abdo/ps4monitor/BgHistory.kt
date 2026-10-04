package com.abdo.ps4monitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One element of ezRemote's bg_download_history.json. Field names are the ones seen in the real file. */
data class BgEntry(
    val id: Long, val name: String, val dir: String, val srcPath: String,
    val fileSize: Long, val transferred: Long, val state: Int, val failed: Int, val type: Int, val timestamp: Long
) {
    val pct: Int? get() = if (fileSize > 0) (transferred * 100 / fileSize).toInt().coerceIn(0, 100) else null
}

/**
 * The PS4 side of ezRemote keeps its background-download queue in bg_download_history.json.
 * What is known (from the user's own file): file_size, bytes_transfered, state, failed_attempts, dest_path, id.
 * Observed: a download that reached failed_attempts = 5 stays stopped; writing 1 back lets it run again.
 *
 * This object only READS the file and PATCHES the single number "failed_attempts" of one entry (by id). Everything else in the
 * file is kept byte for byte (text-level replacement, not a JSON re-serialisation). A backup of the previous text is kept
 * on the phone before every write, and every write is verified by reading the file again.
 */
object BgHistory {
    const val FILE = "bg_download_history.json"
    val entries = MutableStateFlow<Map<String, List<BgEntry>>>(emptyMap())     // ps4Id -> entries (last successful read)
    val status = MutableStateFlow<Map<String, String>>(emptyMap())             // ps4Id -> short problem text ("" = fine)
    private val lock = ReentrantLock()
    private val found = ConcurrentHashMap<String, String>()
    private val retryAt = ConcurrentHashMap<String, Long>()

    /** failed_attempts >= this value means "ezRemote has stopped this download" (the user's file showed 5; may really be 3). */
    fun detect() = Store.sp.getInt("bgdetect", 5)
    private fun setStatus(id: String, m: String) = status.update { it + (id to m) }

    // ---------------- location ----------------
    private fun remember(p: Ps4, path: String): String { found[p.id] = path; DownloadMonitor.d("History file found: $path"); return path }

    /** Manual path (Advanced) wins. Otherwise look for the file in /data and one level below it. The answer is cached. */
    private fun locate(p: Ps4): String? {
        val manual = Store.sp.getString("bgpath", "").orEmpty().trim()
        if (manual.isNotEmpty()) return manual
        found[p.id]?.let { return it }
        val now = System.currentTimeMillis()
        if (now < (retryAt[p.id] ?: 0L)) return null
        retryAt[p.id] = now + 120_000
        val top = try { PkgInspector.browse(p, "/data") } catch (e: Exception) {
            DownloadMonitor.d("History file search: cannot list /data (${e.javaClass.simpleName}: ${e.message})"); return null }
        if (top.any { !it.isDir && it.name == FILE }) return remember(p, "/data/$FILE")
        val dirs = top.filter { it.isDir }.sortedByDescending { it.name.contains("ezremote", true) }.take(40)
        for (d in dirs) {
            val l = try { PkgInspector.browse(p, "/data/${d.name}") } catch (e: Exception) { continue }
            if (l.any { !it.isDir && it.name == FILE }) return remember(p, "/data/${d.name}/$FILE")
        }
        DownloadMonitor.d("History file search: $FILE not found under /data (set its path in Settings > Advanced)")
        return null
    }

    // ---------------- raw access (web first, FTP fallback) ----------------
    private fun ftpRead(p: Ps4, path: String, s: Settings): String {
        val f = Ftp.connect(Conn(p.host, p.ftpPort, p.ftpUser, p.ftpPass), s)
        try {
            f.setDataTimeout(s.timeout * 1000)
            val ins = f.retrieveFileStream(path) ?: throw IOException("RETR failed: " + f.replyString.orEmpty().trim().take(80))
            val bytes = ins.use { it.readBytes() }
            runCatching { f.completePendingCommand() }
            if (bytes.size > 4 * 1024 * 1024) throw IOException("History file is unexpectedly large")
            return String(bytes, Charsets.UTF_8)
        } finally { runCatching { f.logout() }; runCatching { f.disconnect() } }
    }
    private fun ftpWrite(p: Ps4, path: String, text: String, s: Settings) {
        val f = Ftp.connect(Conn(p.host, p.ftpPort, p.ftpUser, p.ftpPass), s)
        try {
            f.setDataTimeout(s.timeout * 1000)
            if (!f.storeFile(path, ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))) throw IOException("STOR failed: " + f.replyString.orEmpty().trim().take(80))
        } finally { runCatching { f.logout() }; runCatching { f.disconnect() } }
    }
    private fun readText(p: Ps4, path: String, s: Settings): String {
        var last: Exception? = null
        if (PkgInspector.httpOn(p)) try {
            val t = EzRemote.getContent(p, path, s.timeout * 1000)
            if (t.isNotBlank()) return t
            last = IOException("ezRemote returned an empty file")
        } catch (e: Exception) { last = e }
        if (PkgInspector.ftpOn(p)) try { return ftpRead(p, path, s) } catch (e: Exception) { last = e }
        throw last ?: IOException("No connection method is enabled for this PS4")
    }

    // ---------------- parsing / matching ----------------
    fun parse(text: String): List<BgEntry> {
        val a = JSONArray(text.trim())
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o ->
                val dest = o.optString("dest_path")
                BgEntry(o.optLong("id"), dest.substringAfterLast('/'), dest.substringBeforeLast('/', "/").ifEmpty { "/" }, o.optString("src_path"),
                    o.optLong("file_size"), o.optLong("bytes_transfered"), o.optInt("state"), o.optInt("failed_attempts"), o.optInt("type"), o.optLong("timestamp"))
            }
        }
    }
    private fun base(n: String) = n.lowercase().removeSuffix(".tmp").removeSuffix(".pkg")

    /** The history entry that belongs to [d]: same folder and same file name (ignoring .tmp / .pkg); newest wins. */
    fun match(d: Download, list: List<BgEntry>? = entries.value[d.ps4Id]): BgEntry? {
        if (list.isNullOrEmpty()) return null
        val dir = DownloadMonitor.norm(d.dest)
        val inDir = list.filter { DownloadMonitor.norm(it.dir) == dir }
        val names = listOfNotNull(d.tempPath, d.finalPath).map { base(it.substringAfterLast('/')) }.toSet()
        val hits = if (names.isNotEmpty()) inDir.filter { base(it.name) in names } else inDir.filter { base(it.name) == base(d.displayName) }
        return hits.maxByOrNull { it.timestamp }
    }

    // ---------------- refresh (called by the monitor loop) ----------------
    /** Blocking. Never throws. Updates [entries]; problems only go to the log / [status], never to a download's state. */
    fun refresh(p: Ps4, s: Settings) {
        if (!PkgInspector.canRead(p)) return
        val path = locate(p)
        if (path == null) { setStatus(p.id, tr("ezRemote history file not found (see Settings > Advanced).", "لم يُعثر على ملف سجل ezRemote (انظر الإعدادات > متقدم).")); return }
        try {
            val l = lock.withLock { parse(readText(p, path, s)) }
            entries.update { it + (p.id to l) }
            setStatus(p.id, "")
        } catch (e: Exception) {
            DownloadMonitor.d("History file read failed: ${e.javaClass.simpleName}: ${e.message}")
            setStatus(p.id, tr("Could not read the ezRemote history file.", "تعذّرت قراءة ملف سجل ezRemote."))
        }
    }

    /** Settings > Advanced "Test": forgets the cached path, searches again, reads and parses. Returns a message for the user. */
    fun test(p: Ps4): String {
        found.remove(p.id); retryAt.remove(p.id)
        val s = Store.settings()
        val path = locate(p) ?: return tr("The file was not found. Enter its full path manually.", "لم يُعثر على الملف. أدخل مساره الكامل يدويًا.")
        return try {
            val l = lock.withLock { parse(readText(p, path, s)) }
            entries.update { it + (p.id to l) }
            tr("OK: $path — ${l.size} download(s) in the file.", "تم: $path — ${l.size} تحميل في الملف.")
        } catch (e: Exception) { tr("Found $path but could not read it: ", "وُجد $path لكن تعذّرت قراءته: ") + (e.message ?: e.javaClass.simpleName) }
    }

    // ---------------- writing failed_attempts ----------------
    /** Replaces the number after "failed_attempts" inside the object whose "id" is [id]. Everything else is untouched. */
    fun patch(text: String, id: Long, value: Int): String? {
        val m = Regex(""""id"\s*:\s*$id(?![0-9])""").find(text) ?: return null
        val start = text.lastIndexOf('{', m.range.first); val end = text.indexOf('}', m.range.last)
        if (start < 0 || end < 0) return null
        val seg = text.substring(start, end + 1)
        val re = Regex(""""failed_attempts"\s*:\s*-?\d+""")
        if (!re.containsMatchIn(seg)) return null
        return text.substring(0, start) + re.replaceFirst(seg, "\"failed_attempts\":$value") + text.substring(end + 1)
    }

    /** Returns null on success (written AND verified by reading the file again), otherwise a message for the user. */
    fun setFailed(p: Ps4, entryId: Long, value: Int, s: Settings): String? = lock.withLock<String?> {
        val path = locate(p) ?: return@withLock tr("The ezRemote history file was not found.", "لم يُعثر على ملف سجل ezRemote.")
        val text = try { readText(p, path, s) } catch (e: Exception) { return@withLock tr("Could not read the history file: ", "تعذّرت قراءة ملف السجل: ") + (e.message ?: "") }
        val known = runCatching { parse(text) }.getOrNull() ?: return@withLock tr("The history file is not valid JSON; nothing was changed.", "ملف السجل ليس JSON صالحًا؛ لم يُغيَّر شيء.")
        if (known.none { it.id == entryId }) return@withLock tr("This download is no longer in the history file.", "هذا التحميل لم يعد في ملف السجل.")
        val patched = patch(text, entryId, value) ?: return@withLock tr("Could not locate failed_attempts for this download in the file.", "تعذّر العثور على failed_attempts لهذا التحميل في الملف.")
        if (runCatching { JSONArray(patched) }.isFailure) return@withLock tr("The edited text was not valid JSON; nothing was written.", "النص المعدَّل ليس JSON صالحًا؛ لم يُكتب شيء.")
        runCatching { File(DownloadMonitor.app.filesDir, "bg_download_history.backup.json").writeText(text) }     // last text before our change
        val methods = ArrayList<String>()
        if (PkgInspector.httpOn(p)) methods += "web"
        if (PkgInspector.ftpOn(p)) methods += "ftp"
        var why = ""
        for (m in methods) {
            try {
                if (m == "web") { val r = EzRemote.edit(p, path, patched); if (!r.ok) why = r.message } else ftpWrite(p, path, patched, s)
                Thread.sleep(400)
                val back = parse(readText(p, path, s))
                if (back.firstOrNull { it.id == entryId }?.failed == value) {
                    entries.update { it + (p.id to back) }
                    DownloadMonitor.d("History file: failed_attempts=$value written via $m and verified")
                    return@withLock null
                }
                why = tr("the PS4 did not keep the new value", "لم يحتفظ الـPS4 بالقيمة الجديدة")
                DownloadMonitor.d("History file write via $m was not kept")
            } catch (e: Exception) { why = e.message ?: e.javaClass.simpleName; DownloadMonitor.d("History file write via $m failed: ${e.javaClass.simpleName}: ${e.message}") }
        }
        tr("Writing the history file failed: ", "فشلت الكتابة في ملف السجل: ") + why
    }
}
