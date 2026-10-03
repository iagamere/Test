package com.abdo.ps4monitor
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.net.*

class FtpError(msg: String) : Exception(msg)     // fatal (e.g. bad credentials)
class SoftError(msg: String) : Exception(msg)    // folder missing / permission: keep retrying

object Ftp {
    fun friendly(e: Exception) = when (e) {
        is FtpError, is SoftError -> e.message ?: "FTP error"
        is SocketTimeoutException -> "Timeout"
        is ConnectException, is NoRouteToHostException, is UnknownHostException ->
            if (!DownloadMonitor.net.value) "Network Unavailable" else "FTP Connection Failed"
        else -> "FTP Connection Failed"
    }

    fun connect(c: Conn, s: Settings): FTPClient {
        val f = FTPClient()
        val ms = s.timeout * 1000
        f.connectTimeout = ms; f.defaultTimeout = ms
        try {
            f.connect(c.host, c.port)
            f.soTimeout = ms
            if (!FTPReply.isPositiveCompletion(f.replyCode)) throw IOException("FTP server unavailable")
            val ok = if (c.user.isBlank()) f.login("anonymous", "anonymous") else f.login(c.user, c.pass)
            if (!ok) throw FtpError("FTP Authentication Failed")
            f.enterLocalPassiveMode()
            f.setFileType(FTP.BINARY_FILE_TYPE)
            return f
        } catch (e: Exception) { runCatching { f.disconnect() }; throw e }
    }

    /** One short-lived session: connect, list, quit. No connection is kept idle between polls. */
    fun listOnce(c: Conn, s: Settings, dir: String): List<FsEntry> {
        val f = connect(c, s)
        try { enterDir(f, dir); return list(f, dir, true).first }
        finally { runCatching { f.logout() }; runCatching { f.disconnect() } }
    }

    fun enterDir(f: FTPClient, dir: String) {
        if (!f.changeWorkingDirectory(dir)) {
            val r = f.replyString.orEmpty().lowercase()
            throw SoftError(if ("denied" in r || "permission" in r) "Permission Denied" else "File Not Found ($dir)")
        }
    }

    // Metadata only: MLSD if the server supports it, otherwise LIST. Never reads file content.
    fun list(f: FTPClient, dir: String, mlsd: Boolean): Pair<List<FsEntry>, Boolean> {
        var use = mlsd
        var arr = if (use) f.mlistDir(dir) else null
        if (arr == null || arr.isEmpty()) {
            arr = f.listFiles(dir)
            if (use && arr.isNotEmpty()) use = false     // server has no working MLSD: stick to LIST
        }
        val out = arr.filter { it != null && !it.isDirectory && !it.isSymbolicLink && it.name != null }
            .map { FsEntry(it.name.substringAfterLast('/'), it.size, it.timestamp?.timeInMillis ?: 0L) }
            .filter { it.name.isNotEmpty() && it.name != "." && it.name != ".." }
        return out to use
    }

    /** Folder listing for the Files screen (includes sub-folders). Short-lived session. */
    fun browse(c: Conn, s: Settings, dir: String): List<FsEntry> {
        val f = connect(c, s)
        try {
            enterDir(f, dir)
            var arr = f.mlistDir(dir)
            if (arr == null || arr.isEmpty()) arr = f.listFiles(dir)
            return arr.filter { it != null && it.name != null }
                .map { FsEntry(it.name.substringAfterLast('/'), it.size, it.timestamp?.timeInMillis ?: 0L, it.isDirectory) }
                .filter { it.name.isNotEmpty() && it.name != "." && it.name != ".." }
        } finally { runCatching { f.logout() }; runCatching { f.disconnect() } }
    }

    private const val MAX_SKIP = 64L shl 20

    /**
     * Reads [length] bytes starting at [offset] (FTP REST + RETR), then drops the connection: the rest of the file is never transferred.
     * If the server refuses REST, the first [offset] bytes are read and discarded (capped at 64 MB).
     */
    fun readRange(c: Conn, s: Settings, path: String, offset: Long, length: Int): ByteArray {
        require(length > 0)
        val f = connect(c, s)
        try {
            f.setDataTimeout(s.timeout * 1000)
            var skip = 0L
            var ins: java.io.InputStream? = null
            if (offset > 0) { f.setRestartOffset(offset); ins = f.retrieveFileStream(path); if (ins == null) { f.setRestartOffset(0); skip = offset } }
            if (ins == null) ins = f.retrieveFileStream(path) ?: throw IOException("RETR failed: " + f.replyString.orEmpty().trim().take(80))
            if (skip > 0) {
                if (skip > MAX_SKIP) { runCatching { ins.close() }; throw IOException("Server does not support REST; offset too large") }
                var left = skip; val tmp = ByteArray(65536)
                while (left > 0) { val r = ins.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt()); if (r < 0) break; left -= r }
            }
            val buf = ByteArray(length); var n = 0
            while (n < length) { val r = ins.read(buf, n, length - n); if (r < 0) break; n += r }
            runCatching { ins.close() }
            return buf.copyOf(n)
        } finally { runCatching { f.disconnect() } }
    }
}
