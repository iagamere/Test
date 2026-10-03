package com.abdo.ps4monitor
import org.json.JSONArray
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

    private fun conn(ps4: Ps4, path: String, timeoutMs: Int): HttpURLConnection {
        val c = URL("http://${ps4.host}:${ps4.httpPort}$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.doOutput = true
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
            FsEntry(n, size, 0L, dir)
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
