package com.abdo.ps4monitor
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONObject
import java.io.IOException
import java.net.*

class EzError(msg: String) : IOException(msg)

/**
 * Only endpoints confirmed from the ezRemote source are used:
 *   POST /__local__/download_url   {url, dest, use_alldebrid:false, use_realdebrid:false}
 *   POST /__local__/list           {path, onlyFolders:false}
 * HTTP 200 / success=true means "request accepted" and nothing more.
 */
object EzRemote {
    sealed class Submit {
        object Accepted : Submit()
        data class Rejected(val message: String, val http: Int) : Submit()
        data class Unreachable(val message: String) : Submit()
    }
    @Volatile private var loggedListShape = false

    private fun conn(ps4: Ps4, path: String, timeoutMs: Int, readMs: Int = timeoutMs): HttpURLConnection {
        val c = URL("http://${ps4.host}:${ps4.httpPort}$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = timeoutMs; c.readTimeout = readMs; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        return c
    }
    private fun readBody(c: HttpURLConnection): String =
        runCatching { (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")

    fun friendly(e: Throwable): String = when (e) {
        is SocketTimeoutException -> "The PS4 did not answer in time."
        is ConnectException, is NoRouteToHostException -> "Cannot reach the PS4 web server. Is ezRemote running with its web server enabled?"
        is UnknownHostException -> "PS4 address not found."
        is EzError -> e.message ?: "ezRemote error"
        else -> "PS4 web connection problem."
    }

    fun submit(ps4: Ps4, url: String, dest: String): Submit {
        val body = JSONObject().put("url", url).put("dest", dest).put("use_alldebrid", false).put("use_realdebrid", false).toString()
        val c = try { conn(ps4, "/__local__/download_url", 30_000) } catch (e: Exception) { return Submit.Unreachable(friendly(e)) }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = readBody(c)
            DownloadMonitor.d("ezRemote response: HTTP $code ${text.take(200)}")      // url/body only; no credentials involved
            if (code !in 200..299) return Submit.Rejected("ezRemote answered HTTP $code.", code)
            val r = runCatching { JSONObject(text).optJSONObject("result") }.getOrNull()
            if (r != null && !r.optBoolean("success", false)) {
                val err = r.optString("error").takeIf { it.isNotBlank() && it != "null" }
                return Submit.Rejected("ezRemote rejected the request" + (err?.let { ": $it" } ?: "."), code)
            }
            if (r == null) DownloadMonitor.d("ezRemote response had no result object; treated as accepted (HTTP $code)")
            return Submit.Accepted
        } catch (e: Exception) {
            DownloadMonitor.d("ezRemote submit error: ${e.javaClass.simpleName}: ${e.message}")
            return Submit.Unreachable(friendly(e))
        } finally { c.disconnect() }
    }

    /** Filesystem listing. The exact JSON envelope of /__local__/list is NOT confirmed, so parsing is tolerant (see findArray). */
    fun list(ps4: Ps4, path: String, timeoutMs: Int): List<FsEntry> {
        val c = conn(ps4, "/__local__/list", timeoutMs)
        try {
            c.outputStream.use { it.write(JSONObject().put("path", path).put("onlyFolders", false).toString().toByteArray()) }
            val code = c.responseCode
            val text = readBody(c)
            if (code !in 200..299) throw EzError("ezRemote list answered HTTP $code")
            if (!loggedListShape) { loggedListShape = true; DownloadMonitor.d("First /__local__/list reply (shape check): ${text.take(300)}") }
            return parse(text)
        } finally { c.disconnect() }
    }

    // ---- confirmed from ezRemote http_server.cpp ----
    /** "date" is "YYYY-MM-DD HH:MM:SS" (PS4 wall clock). Parsed with the phone zone so it is displayed unchanged. */
    private fun parseDate(t: String): Long = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(t)?.time ?: 0L }.getOrDefault(0L)

    class OpResult(val ok: Boolean, val message: String)
    private fun post(ps4: Ps4, path: String, body: org.json.JSONObject, timeoutMs: Int, readMs: Int = timeoutMs): OpResult {
        val c = conn(ps4, path, timeoutMs, readMs)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode; val text = readBody(c)
            if (code !in 200..299) return OpResult(false, "ezRemote answered HTTP $code.")
            val r = runCatching { JSONObject(text).optJSONObject("result") }.getOrNull()
            return if (r != null && r.optBoolean("success", false)) OpResult(true, "")
                   else OpResult(false, r?.optString("error")?.takeIf { it.isNotBlank() && it != "null" } ?: "ezRemote refused the request.")
        } finally { c.disconnect() }
    }
    /** POST /__local__/remove {"items":[...]}  — recursive on the PS4 (FS::RmRecursive); refused while ezRemote is busy with another activity. */
    fun remove(ps4: Ps4, items: List<String>, timeoutMs: Int) = post(ps4, "/__local__/remove", JSONObject().put("items", JSONArray(items)), timeoutMs)
    /** POST /__local__/rename {"item","newItemPath"} — ezRemote ignores the result of the rename and always answers success, so callers must verify by listing. */
    fun rename(ps4: Ps4, from: String, to: String, timeoutMs: Int) = post(ps4, "/__local__/rename", JSONObject().put("item", from).put("newItemPath", to), timeoutMs)

    // ---- more confirmed endpoints (request shapes read from http_server.cpp) ----
    /** POST /__local__/install {"items":[paths]} — InstallLocalPkg. Refused while ezRemote is busy. Synchronous, so give it time. */
    fun install(ps4: Ps4, paths: List<String>, readMs: Int) = post(ps4, "/__local__/install", JSONObject().put("items", JSONArray(paths)), 10_000, readMs)
    /** POST /__local__/createFolder {"newPath"} — the result of MkDirs is ignored by ezRemote, verify by listing. */
    fun createFolder(ps4: Ps4, path: String) = post(ps4, "/__local__/createFolder", JSONObject().put("newPath", path), 10_000, 30_000)
    /** POST /__local__/move | /copy {"items":[..],"newPath":destinationFolder}. Synchronous, can take very long for big files. */
    fun move(ps4: Ps4, paths: List<String>, newPath: String, readMs: Int) = post(ps4, "/__local__/move", JSONObject().put("items", JSONArray(paths)).put("newPath", newPath), 10_000, readMs)
    fun copy(ps4: Ps4, paths: List<String>, newPath: String, readMs: Int) = post(ps4, "/__local__/copy", JSONObject().put("items", JSONArray(paths)).put("newPath", newPath), 10_000, readMs)
    /** POST /__local__/extract {"item","destination","folderName"} — answers "Unsupported compressed file format" for unknown archives. */
    fun extract(ps4: Ps4, item: String, destination: String, folderName: String, readMs: Int) =
        post(ps4, "/__local__/extract", JSONObject().put("item", item).put("destination", destination).put("folderName", folderName), 10_000, readMs)
    /** POST /__local__/install_url — streams the package to the PS4 installer (enable_rpi) without keeping a copy (use_disk_cache=false). */
    fun installUrl(ps4: Ps4, url: String, readMs: Int) = post(ps4, "/__local__/install_url",
        JSONObject().put("url", url).put("use_alldebrid", false).put("use_realdebrid", false).put("use_disk_cache", false).put("enable_rpi", true), 10_000, readMs)
    /** POST /__local__/edit {"item","content"} */
    fun edit(ps4: Ps4, path: String, content: String) = post(ps4, "/__local__/edit", JSONObject().put("item", path).put("content", content), 10_000, 30_000)
    /** POST /__local__/getContent {"item"} -> {"result":"<text>"} */
    fun getContent(ps4: Ps4, path: String, timeoutMs: Int): String {
        val c = conn(ps4, "/__local__/getContent", timeoutMs)
        try {
            c.outputStream.use { it.write(JSONObject().put("item", path).toString().toByteArray()) }
            val code = c.responseCode; val text = readBody(c)
            if (code !in 200..299) throw EzError("ezRemote answered HTTP $code.")
            return JSONObject(text).optString("result")
        } finally { c.disconnect() }
    }
    /** GET /__local__/uploadResumeSize?destination=&filename= -> {"size":N} (0 when the file does not exist): a cheap single-file size query. */
    fun fileSize(ps4: Ps4, path: String, timeoutMs: Int): Long {
        val dir = path.substringBeforeLast('/', "/").ifEmpty { "/" }; val name = path.substringAfterLast('/')
        val u = "http://${ps4.host}:${ps4.httpPort}/__local__/uploadResumeSize?destination=" + java.net.URLEncoder.encode(dir, "UTF-8") + "&filename=" + java.net.URLEncoder.encode(name, "UTF-8")
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        try { if (c.responseCode !in 200..299) throw EzError("ezRemote answered HTTP ${c.responseCode}."); return JSONObject(readBody(c)).optLong("size", 0L) } finally { c.disconnect() }
    }
    /**
     * POST /__local__/upload (multipart, chunked). Field ORDER matters in ezRemote: destination and the _chunk* fields must come before "file",
     * and "file" must be last (any later part would be written into the file). _chunkNumber 0 creates/truncates, >0 appends.
     * ezRemote always answers success, so the caller verifies the size with [fileSize].
     */
    fun uploadChunk(ps4: Ps4, destDir: String, name: String, total: Long, chunkNumber: Int, data: ByteArray, len: Int, timeoutMs: Int): OpResult {
        val b = "----PS4Monitor" + System.nanoTime()
        fun field(n: String, v: String) = "--$b\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n"
        val head = (field("destination", destDir) + field("_chunkSize", len.toString()) + field("_chunkNumber", chunkNumber.toString()) +
            field("_totalSize", total.toString()) + field("_currentChunkSize", len.toString()) +
            "--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "_")}\"\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$b--\r\n".toByteArray(Charsets.UTF_8)
        val c = conn(ps4, "/__local__/upload", 10_000, timeoutMs)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
        c.setFixedLengthStreamingMode(head.size.toLong() + len + tail.size)
        try {
            c.outputStream.use { it.write(head); it.write(data, 0, len); it.write(tail) }
            val code = c.responseCode; readBody(c)
            return if (code in 200..299) OpResult(true, "") else OpResult(false, "ezRemote answered HTTP $code.")
        } finally { c.disconnect() }
    }

    /**
     * GET /__local__/downloadFile?path=...  with a Range header: reads [length] bytes at [offset] and drops the connection.
     * Whether this ezRemote build honours Range is not visible in the source (it depends on its httplib); if it answers 200 the
     * first bytes are read (or skipped, capped at 64 MB) and the rest of the file is never pulled.
     * Only call this for paths that were just listed: the handler opens the file without checking that it exists.
     */
    fun readRange(ps4: Ps4, path: String, offset: Long, length: Int, timeoutMs: Int): ByteArray {
        val c = URL("http://${ps4.host}:${ps4.httpPort}/__local__/downloadFile?path=" + java.net.URLEncoder.encode(path, "UTF-8")).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
        try {
            val code = c.responseCode
            if (code != 206 && code != 200) throw EzError("ezRemote file read answered HTTP $code")
            val ins = c.inputStream
            var skip = if (code == 200 && offset > 0) offset else 0L
            if (skip > (64L shl 20)) throw EzError("ezRemote ignored the range request and the offset is too large")
            val tmp = ByteArray(65536)
            while (skip > 0) { val r = ins.read(tmp, 0, minOf(skip, tmp.size.toLong()).toInt()); if (r < 0) break; skip -= r }
            val buf = ByteArray(length); var n = 0
            while (n < length) { val r = ins.read(buf, n, length - n); if (r < 0) break; n += r }
            return buf.copyOf(n)
        } finally { c.disconnect() }      // closes the socket without draining a multi-GB body
    }

    fun parse(text: String): List<FsEntry> {
        val t = text.trim()
        val arr: JSONArray = when {
            t.startsWith("[") -> JSONArray(t)
            t.startsWith("{") -> {
                val o = JSONObject(t)
                o.optJSONObject("result")?.let { r -> if (r.has("success") && !r.optBoolean("success")) throw EzError(r.optString("error", "list failed")) }
                findArray(o) ?: if (o.length() == 0 || isEmptyListEnvelope(o)) JSONArray() else throw EzError("Unrecognised list reply")
            }
            else -> throw EzError("Unrecognised list reply")
        }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val n = o.optString("name").substringAfterLast('/'); if (n.isEmpty() || n == "." || n == "..") return@mapNotNull null
            val type = o.opt("type")?.toString()?.lowercase().orEmpty()
            val rights = o.optString("rights")
            val dir = type == "d" || "dir" in type || "folder" in type || rights.startsWith("d")
            val size = when (val v = o.opt("size")) { is Number -> v.toLong(); is String -> v.toLongOrNull() ?: 0L; else -> 0L }
            FsEntry(n, size, parseDate(o.optString("date")), dir)
        }
    }
    private fun isEmptyListEnvelope(o: JSONObject) = o.keys().asSequence().all { k -> o.opt(k).let { it !is JSONArray || it.length() == 0 } }
    private fun findArray(o: JSONObject): JSONArray? {
        for (k in o.keys()) {
            val v = o.opt(k)
            if (v is JSONArray && (v.length() == 0 || v.optJSONObject(0)?.has("name") == true)) return v
            if (v is JSONObject) findArray(v)?.let { return it }
        }
        return null
    }
}

data class SizeInfo(val bytes: Long, val uncertain: Boolean, val note: String)

/** Phone-side server size check (preserved from v1). Only an exact, twice-confirmed answer is used automatically. */
object Net {
    private data class Resp(val code: Int, val length: Long, val type: String, val range: String?)
    private fun fetch(url: String, method: String, range: Boolean): Resp? {
        var u = url
        for (i in 0 until 6) {
            val c = URL(u).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method; c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "Mozilla/5.0"); c.setRequestProperty("Accept-Encoding", "identity")
                if (range) c.setRequestProperty("Range", "bytes=0-0")
                val code = c.responseCode
                if (code in 300..399) { val loc = c.getHeaderField("Location") ?: return null; u = URL(URL(u), loc).toString(); continue }
                return Resp(code, c.contentLengthLong, c.contentType ?: "", c.getHeaderField("Content-Range"))
            } finally { c.disconnect() }
        }
        return null
    }
    fun size(url: String): SizeInfo? {
        val h = runCatching { fetch(url, "HEAD", false) }.getOrNull()
        val hl = if (h != null && h.code in 200..299 && h.length > 0 && !h.type.startsWith("text/html")) h.length else null
        val r = runCatching { fetch(url, "GET", true) }.getOrNull()
        var rl: Long? = null
        if (r != null && !r.type.startsWith("text/html")) {
            rl = r.range?.substringAfter('/')?.toLongOrNull()
            if (rl == null && r.code == 200 && r.length > 1) rl = r.length
        }
        return when {
            rl != null && hl != null && rl != hl -> SizeInfo(rl, true, "uncertain: HEAD says $hl, range says $rl")
            rl != null && hl != null -> SizeInfo(rl, false, "confirmed twice by the server")
            rl != null -> SizeInfo(rl, false, "from the server")
            hl != null -> SizeInfo(hl, false, "from the server")
            else -> null
        }
    }
}
