package com.abdo.ps4monitor
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * PS4 PKG reader. Everything here is derived from the PKG container layout (big-endian header, 32-byte entry table, PSF "param.sfo")
 * as documented by the community (psdevwiki / shadPS4). It is NOT derived from the ezRemote source and has not been verified against
 * your own files yet; every field is range-checked and the raw numbers are shown so a wrong assumption is visible, not hidden.
 * Only unencrypted entries are read (param.sfo, icon0.png, pic*.png). Nothing is decrypted.
 */
class PkgError(msg: String, val notPkg: Boolean = false) : Exception(msg)

data class PkgEntry(val id: Long, val name: String, val offset: Long, val size: Long, val encrypted: Boolean)

data class PkgInfo(
    val contentId: String, val title: String?, val titleId: String?, val appVer: String?, val version: String?,
    val category: String?, val systemVer: String?, val region: String?,
    val totalSize: Long?, val sizeTrusted: Boolean, val sizeNote: String,
    val contentTypeHex: String, val drmHex: String, val flagsHex: String, val versionDateHex: String,
    val bodyOffset: Long, val bodySize: Long, val pfsOffset: Long, val pfsSize: Long, val rawPkgSize: Long, val entryCount: Int,
    val params: List<Pair<String, String>>, val entries: List<PkgEntry>,
    val hasIcon: Boolean, val hasPic0: Boolean, val hasPic1: Boolean,
    val fileSizeAtRead: Long, val readAt: Long, val missing: List<String>, val complete: Boolean, val full: Boolean
)

object PkgFormat {
    const val MAGIC = 0x7F434E54L
    const val HEADER_MIN = 0x458
    const val ID_NAMES = 0x200L; const val ID_PARAM = 0x1000L; const val ID_ICON0 = 0x1200L; const val ID_PIC0 = 0x1220L; const val ID_PIC1 = 0x1006L

    private fun need(b: ByteArray, o: Int, n: Int) { if (o < 0 || o + n > b.size) throw PkgError("Truncated data at 0x${o.toString(16)}") }
    fun u16(b: ByteArray, o: Int): Int { need(b, o, 2); return ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF) }
    fun u32(b: ByteArray, o: Int): Long { need(b, o, 4); return ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF) }
    fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
    fun l16(b: ByteArray, o: Int): Int { need(b, o, 2); return (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) }
    fun l32(b: ByteArray, o: Int): Long { need(b, o, 4); return (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24) }
    fun cstr(b: ByteArray, o: Int): String {
        if (o < 0 || o >= b.size) return ""
        var e = o; while (e < b.size && b[e].toInt() != 0) e++
        return String(b, o, e - o, Charsets.UTF_8)
    }
    fun hex(v: Long, w: Int = 8) = "0x" + java.lang.Long.toHexString(v).uppercase().padStart(w, '0')

    class Header(val type: Long, val entryCount: Int, val tableOffset: Long, val bodyOffset: Long, val bodySize: Long, val contentOffset: Long, val contentSize: Long,
                 val contentId: String, val drm: Long, val contentType: Long, val contentFlags: Long, val versionDate: Long,
                 val pfsOffset: Long, val pfsSize: Long, val pkgSize: Long)

    fun parseHeader(b: ByteArray): Header {
        if (b.size < 4 || u32(b, 0) != MAGIC) throw PkgError("Not a PS4 PKG (magic mismatch)", notPkg = true)
        if (b.size < HEADER_MIN) throw PkgError("The start of the file is not available yet")
        return Header(u32(b, 4), u32(b, 0x10).toInt(), u32(b, 0x18), u64(b, 0x20), u64(b, 0x28), u64(b, 0x30), u64(b, 0x38),
            String(b, 0x40, 0x24, Charsets.US_ASCII).trimEnd('\u0000').trim(), u32(b, 0x70), u32(b, 0x74), u32(b, 0x78), u32(b, 0x80),
            u64(b, 0x428), u64(b, 0x430), u64(b, 0x448))
    }

    class Raw(val id: Long, val nameOff: Long, val flags1: Long, val offset: Long, val size: Long)
    fun parseTable(b: ByteArray, base: Int, count: Int): List<Raw> = (0 until count).map { i ->
        val o = base + i * 32; Raw(u32(b, o), u32(b, o + 4), u32(b, o + 8), u32(b, o + 16), u32(b, o + 20)) }

    val KNOWN = mapOf(0x1L to "digests", 0x10L to "entry_keys", 0x20L to "image_key", 0x80L to "general_digests", 0x100L to "metas", 0x200L to "entry_names",
        0x400L to "license.dat", 0x401L to "license.info", 0x402L to "nptitle.dat", 0x403L to "npbind.dat", 0x404L to "selfinfo.dat", 0x406L to "imageinfo.dat",
        0x407L to "target-deltainfo.dat", 0x408L to "origin-deltainfo.dat", 0x409L to "psreserved.dat",
        0x1000L to "param.sfo", 0x1001L to "playgo-chunk.dat", 0x1002L to "playgo-chunk.sha", 0x1003L to "playgo-manifest.xml", 0x1004L to "pronunciation.xml",
        0x1005L to "pronunciation.sig", 0x1006L to "pic1.png", 0x1007L to "pubtoolinfo.dat", 0x100BL to "shareparam.json", 0x100CL to "shareoverlayimage.png",
        0x100DL to "save_data.png", 0x100EL to "shareprivacyguardimage.png", 0x1200L to "icon0.png", 0x1220L to "pic0.png", 0x1240L to "snd0.at9")

    fun entryName(r: Raw, names: ByteArray?): String {
        if (names != null && r.nameOff in 0 until names.size.toLong()) cstr(names, r.nameOff.toInt()).takeIf { it.isNotBlank() }?.let { return it }
        return KNOWN[r.id] ?: "entry_" + hex(r.id, 4)
    }

    /** PSF ("param.sfo") — little-endian key/value table. */
    fun parseSfo(b: ByteArray): List<Pair<String, String>> {
        if (b.size < 20 || b[0].toInt() != 0 || b[1] != 'P'.code.toByte() || b[2] != 'S'.code.toByte() || b[3] != 'F'.code.toByte()) throw PkgError("param.sfo has an unexpected format")
        val keyTab = l32(b, 8).toInt(); val dataTab = l32(b, 12).toInt(); val n = l32(b, 16).toInt()
        if (n !in 0..512) throw PkgError("param.sfo entry count looks wrong")
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until n) {
            val e = 20 + i * 16
            val key = cstr(b, keyTab + l16(b, e)); val fmt = l16(b, e + 2); val len = l32(b, e + 4).toInt(); val off = dataTab + l32(b, e + 12).toInt()
            val v = when (fmt) {
                0x0404 -> if (key == "SYSTEM_VER") sysVer(l32(b, off)) else l32(b, off).toString()
                0x0004, 0x0204 -> { need(b, off, 0); String(b, off, minOf(len, b.size - off).coerceAtLeast(0), Charsets.UTF_8).trimEnd('\u0000') }
                else -> "(" + hex(fmt.toLong(), 4) + ")"
            }
            out += key to v
        }
        return out
    }
    fun sysVer(v: Long) = "${(v shr 24) and 0xFF}.${java.lang.Long.toHexString((v shr 16) and 0xFF).padStart(2, '0')}  (${hex(v)})"

    fun region(cid: String) = when (cid.firstOrNull()) { 'U' -> "USA"; 'E' -> "Europe"; 'J' -> "Japan"; 'H' -> "Asia"; 'K' -> "Korea"; 'I' -> "International"; else -> null }
    fun categoryLabel(c: String?) = when (c) {
        "gd" -> tr("Game / Application", "لعبة / تطبيق"); "gp" -> tr("Update (patch)", "تحديث (باتش)"); "ac" -> tr("DLC / Add-on", "إضافة (DLC)")
        null -> null; else -> c
    }

    /** Total package size from the header. Applied automatically only when two independent header fields agree (see DownloadMonitor). */
    class SizeGuess(val bytes: Long, val trusted: Boolean, val note: String)
    fun guessTotal(h: Header, currentSize: Long): SizeGuess? {
        val a = h.pkgSize; val end = h.pfsOffset + h.pfsSize
        if (a < (1L shl 16) || a > (1L shl 42) || a < currentSize) return null
        return when {
            end > 0 && a == end -> SizeGuess(a, true, "PKG header (agrees with PFS image end)")
            end > 0 && a >= end && a - end <= (1L shl 20) -> SizeGuess(a, true, "PKG header")
            else -> SizeGuess(a, false, "PKG header (unconfirmed)")
        }
    }
}

object PkgStore {
    private lateinit var root: File
    private val mem = object : LruCache<String, Bitmap>(32 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    fun init(c: Context) { root = File(c.filesDir, "pkg").apply { mkdirs() } }
    private fun safe(k: String) = k.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private fun dir(key: String, create: Boolean = false) = File(root, safe(key)).also { if (create) it.mkdirs() }
    fun key(ps4Id: String, path: String, size: Long, mtime: Long = 0): String =
        "f_" + MessageDigest.getInstance("SHA-1").digest("$ps4Id|$path|$size|$mtime".toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    fun save(key: String, info: PkgInfo, art: Map<String, ByteArray>) {
        val d = dir(key, true)
        art.forEach { (n, b) -> File(d, n).writeBytes(b) }
        File(d, "info.json").writeText(toJson(info).toString())
        mem.snapshot().keys.filter { it.startsWith("$key/") }.forEach { mem.remove(it) }
    }
    fun load(key: String): PkgInfo? = runCatching { File(dir(key), "info.json").takeIf { it.exists() }?.let { fromJson(JSONObject(it.readText())) } }.getOrNull()
    fun copy(fromKey: String, toKey: String) {
        val src = dir(fromKey); if (!src.exists()) return
        val dst = dir(toKey, true); src.listFiles()?.forEach { it.copyTo(File(dst, it.name), overwrite = true) }
        mem.snapshot().keys.filter { it.startsWith("$toKey/") }.forEach { mem.remove(it) }
    }
    fun delete(key: String) { dir(key).deleteRecursively(); mem.snapshot().keys.filter { it.startsWith("$key/") }.forEach { mem.remove(it) } }
    fun bytes(key: String, name: String): ByteArray? = File(dir(key), name).takeIf { it.exists() }?.readBytes()

    /** Decoded (and down-scaled to [maxDim] when > 0) image, memory-cached. Call off the main thread the first time. */
    fun bitmap(key: String, name: String, maxDim: Int = 0): Bitmap? {
        val ck = "$key/$name/$maxDim"; mem.get(ck)?.let { return it }
        val f = File(dir(key), name); if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        val limit = if (maxDim > 0) maxDim else 1600
        var sample = 1; while (maxOf(o.outWidth, o.outHeight) / sample > limit * 2) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = if (maxDim > 0 && maxOf(bmp.width, bmp.height) > maxDim) { val sc = maxDim.toFloat() / maxOf(bmp.width, bmp.height); Bitmap.createScaledBitmap(bmp, (bmp.width * sc).toInt().coerceAtLeast(1), (bmp.height * sc).toInt().coerceAtLeast(1), true) } else bmp
        mem.put(ck, out); return out
    }

    private fun toJson(i: PkgInfo) = JSONObject().put("cid", i.contentId).put("title", i.title ?: JSONObject.NULL).put("tid", i.titleId ?: JSONObject.NULL)
        .put("appver", i.appVer ?: JSONObject.NULL).put("ver", i.version ?: JSONObject.NULL).put("cat", i.category ?: JSONObject.NULL).put("sys", i.systemVer ?: JSONObject.NULL)
        .put("reg", i.region ?: JSONObject.NULL).put("total", i.totalSize ?: JSONObject.NULL).put("trusted", i.sizeTrusted).put("snote", i.sizeNote)
        .put("ctype", i.contentTypeHex).put("drm", i.drmHex).put("flags", i.flagsHex).put("vdate", i.versionDateHex)
        .put("bo", i.bodyOffset).put("bs", i.bodySize).put("po", i.pfsOffset).put("ps", i.pfsSize).put("raw", i.rawPkgSize).put("ec", i.entryCount)
        .put("params", JSONArray(i.params.map { JSONArray().put(it.first).put(it.second) }))
        .put("entries", JSONArray(i.entries.map { JSONObject().put("id", it.id).put("n", it.name).put("o", it.offset).put("s", it.size).put("e", it.encrypted) }))
        .put("icon", i.hasIcon).put("pic0", i.hasPic0).put("pic1", i.hasPic1).put("fsz", i.fileSizeAtRead).put("at", i.readAt)
        .put("missing", JSONArray(i.missing)).put("complete", i.complete).put("full", i.full)
    private fun fromJson(o: JSONObject): PkgInfo {
        fun s(k: String) = if (o.isNull(k)) null else o.getString(k)
        val ps = o.getJSONArray("params"); val es = o.getJSONArray("entries"); val ms = o.getJSONArray("missing")
        return PkgInfo(o.getString("cid"), s("title"), s("tid"), s("appver"), s("ver"), s("cat"), s("sys"), s("reg"),
            if (o.isNull("total")) null else o.getLong("total"), o.optBoolean("trusted"), o.optString("snote"),
            o.optString("ctype"), o.optString("drm"), o.optString("flags"), o.optString("vdate"),
            o.optLong("bo"), o.optLong("bs"), o.optLong("po"), o.optLong("ps"), o.optLong("raw"), o.optInt("ec"),
            (0 until ps.length()).map { ps.getJSONArray(it).let { a -> a.getString(0) to a.getString(1) } },
            (0 until es.length()).map { es.getJSONObject(it).let { e -> PkgEntry(e.getLong("id"), e.getString("n"), e.getLong("o"), e.getLong("s"), e.getBoolean("e")) } },
            o.optBoolean("icon"), o.optBoolean("pic0"), o.optBoolean("pic1"), o.optLong("fsz"), o.optLong("at"),
            (0 until ms.length()).map { ms.getString(it) }, o.optBoolean("complete"), o.optBoolean("full"))
    }
}

object PkgInspector {
    val mutex = Mutex()          // one PKG read at a time: the PS4 FTP server is small and also serves the monitor
    class Result(val info: PkgInfo?, val error: String?, val notPkg: Boolean)

    fun ftpOn(p: Ps4) = p.mode != MonitorMode.HTTP_ONLY && p.ftpPort > 0
    fun httpOn(p: Ps4) = p.mode != MonitorMode.FTP_ONLY
    fun canRead(p: Ps4) = httpOn(p) || ftpOn(p)
    private val httpBadUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Partial file read: ezRemote web (Range) first, FTP (REST) as fallback. Never transfers more than [len] bytes. */
    fun readBytes(p: Ps4, path: String, off: Long, len: Int): ByteArray {
        val s = Store.settings(); var last: Exception? = null
        if (httpOn(p) && (httpBadUntil[p.id] ?: 0L) < System.currentTimeMillis()) {
            try { return EzRemote.readRange(p, path, off, len, s.timeout * 1000) }
            catch (e: Exception) { last = e; if (ftpOn(p)) httpBadUntil[p.id] = System.currentTimeMillis() + 60_000; DownloadMonitor.d("File read via web failed: ${e.javaClass.simpleName}: ${e.message}") }
        }
        if (ftpOn(p)) {
            try { return Ftp.readRange(conn(p), s, path, off, len) }
            catch (e: Exception) { last = e; DownloadMonitor.d("File read via FTP failed: ${e.javaClass.simpleName}: ${e.message}") }
        }
        throw last ?: IOException("No connection method is enabled for this PS4")
    }
    /** True when the file starts with the PS4 PKG magic. */
    fun isPkg(p: Ps4, path: String): Boolean { val b = readBytes(p, path, 0, 4); return b.size == 4 && PkgFormat.u32(b, 0) == PkgFormat.MAGIC }
    private fun conn(p: Ps4) = Conn(p.host, p.ftpPort, p.ftpUser, p.ftpPass)
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private fun isPng(b: ByteArray) = b.size > 8 && (0 until 4).all { b[it] == PNG[it] }

    /** Folder listing: FTP first (reliable folder flag), ezRemote /__local__/list as fallback. */
    fun browse(p: Ps4, dir: String): List<FsEntry> {
        val s = Store.settings(); val d = DownloadMonitor.norm(dir)
        var last: Exception? = null
        if (ftpOn(p)) try { return Ftp.browse(conn(p), s, d) } catch (e: Exception) { last = e; DownloadMonitor.d("Browse FTP failed: ${e.javaClass.simpleName}: ${e.message}") }
        if (p.mode != MonitorMode.FTP_ONLY) try { return EzRemote.list(p, d, s.timeout * 1000) } catch (e: Exception) { last = e; DownloadMonitor.d("Browse HTTP failed: ${e.javaClass.simpleName}: ${e.message}") }
        throw last ?: IOException("No connection method is enabled for this PS4")
    }
    fun friendly(e: Exception) = when (e) { is PkgError -> e.message ?: "PKG error"; is EzError -> EzRemote.friendly(e); is SoftError, is FtpError -> e.message ?: "FTP error"; is IOException -> if (e.message?.startsWith("RETR") == true) "The PS4 would not let the file be read." else Ftp.friendly(e); else -> Ftp.friendly(e) }

    fun sizeOf(p: Ps4, path: String): Long? {
        if (httpOn(p)) try { val n = EzRemote.fileSize(p, path, Store.settings().timeout * 1000); if (n > 0) return n } catch (e: Exception) { }      // cheap single-file query
        val dir = path.substringBeforeLast('/', "/").ifEmpty { "/" }; val name = path.substringAfterLast('/')
        return browse(p, dir).firstOrNull { it.name == name }?.size
    }

    fun inspect(p: Ps4, path: String, fileSizeIn: Long?, key: String, full: Boolean): Result {
        if (!canRead(p)) return Result(null, "No web or FTP connection is enabled for this PS4.", false)
        val s = Store.settings(); val c = conn(p)
        try {
            val fsz = fileSizeIn ?: sizeOf(p, path) ?: throw PkgError("Could not read the file size")
            if (fsz < 4) throw PkgError("The file is empty so far")
            val head = readBytes(p, path, 0, minOf(fsz, 0x3000L).toInt())
            val h = PkgFormat.parseHeader(head)
            val missing = ArrayList<String>()
            fun read(off: Long, len: Long, cap: Long): ByteArray? {
                if (len <= 0 || len > cap || off < 0) return null
                if (off + len > fsz) return null
                return readBytes(p, path, off, len.toInt())
            }
            if (h.entryCount !in 1..4096) throw PkgError("Entry count looks wrong (${h.entryCount}); this may not be a standard PS4 PKG")
            val tblLen = h.entryCount * 32L
            val tbl: ByteArray? = if (h.tableOffset + tblLen <= head.size) head.copyOfRange(h.tableOffset.toInt(), (h.tableOffset + tblLen).toInt()) else read(h.tableOffset, tblLen, 256 * 1024)
            var raws = emptyList<PkgFormat.Raw>()
            if (tbl != null) raws = PkgFormat.parseTable(tbl, 0, h.entryCount) else missing += "entry table"
            val names = raws.firstOrNull { it.id == PkgFormat.ID_NAMES }?.let { r -> read(r.offset, r.size, 1L shl 20) ?: run { missing += "entry names"; null } }
            val entries = raws.map { PkgEntry(it.id, PkgFormat.entryName(it, names), it.offset, it.size, (it.flags1 and 0x80000000L) != 0L) }

            var params = emptyList<Pair<String, String>>()
            entries.firstOrNull { it.id == PkgFormat.ID_PARAM }?.let { e ->
                if (e.encrypted) missing += "param.sfo (encrypted)" else {
                    val b = read(e.offset, e.size, 1L shl 20)
                    if (b == null) missing += "param.sfo" else params = try { PkgFormat.parseSfo(b) } catch (x: PkgError) { missing += "param.sfo (${x.message})"; emptyList() }
                }
            }
            val art = HashMap<String, ByteArray>()
            fun image(id: Long, file: String) {
                val e = entries.firstOrNull { it.id == id } ?: return
                if (e.encrypted) { missing += "$file (encrypted)"; return }
                val b = read(e.offset, e.size, 8L shl 20) ?: run { missing += file; return }
                if (isPng(b)) art[file] = b else missing += "$file (not PNG)"
            }
            image(PkgFormat.ID_ICON0, "icon0.png")
            if (full) { image(PkgFormat.ID_PIC1, "pic1.png"); image(PkgFormat.ID_PIC0, "pic0.png") }

            fun pv(k: String) = params.firstOrNull { it.first == k }?.second
            val guess = PkgFormat.guessTotal(h, fsz)
            val info = PkgInfo(h.contentId, pv("TITLE"), pv("TITLE_ID") ?: h.contentId.substringAfter('-', "").substringBefore('_').ifBlank { null }, pv("APP_VER"), pv("VERSION"), pv("CATEGORY"),
                pv("SYSTEM_VER"), PkgFormat.region(h.contentId), guess?.bytes, guess?.trusted == true, guess?.note ?: "PKG header size not usable",
                PkgFormat.hex(h.contentType), PkgFormat.hex(h.drm), PkgFormat.hex(h.contentFlags), PkgFormat.hex(h.versionDate),
                h.bodyOffset, h.bodySize, h.pfsOffset, h.pfsSize, h.pkgSize, h.entryCount, params, entries,
                "icon0.png" in art, "pic0.png" in art, "pic1.png" in art, fsz, System.currentTimeMillis(), missing, missing.isEmpty(), full)
            PkgStore.save(key, info, art)
            DownloadMonitor.d("PKG read: ${info.titleId ?: info.contentId}  title=${info.title}  entries=${entries.size}  headerSize=${h.pkgSize}  pfsEnd=${h.pfsOffset + h.pfsSize}  fileNow=$fsz  missing=${missing.joinToString()}")
            return Result(info, null, false)
        } catch (e: PkgError) {
            DownloadMonitor.d("PKG: ${e.message}")
            return Result(null, e.message, e.notPkg)
        } catch (e: Exception) {
            DownloadMonitor.d("PKG read failed: ${e.javaClass.simpleName}: ${e.message}")
            return Result(null, friendly(e), false)
        }
    }
}

class Thumb(val bmp: Bitmap, val title: String?, val titleId: String?)

/**
 * Automatic artwork for any PKG shown anywhere in the app (Files, Downloads, Home, notifications).
 * Cached on disk; a miss triggers ONE queued header read (never parallel: the PS4 is small). Failures are remembered for a while
 * so scrolling does not hammer the PS4. A partial .tmp is keyed without its size, so its artwork is read once, not on every refresh.
 */
object PkgThumbs {
    private val miss = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun thumbKey(p: Ps4, path: String, size: Long): String =
        if (path.endsWith(".tmp", true)) PkgStore.key(p.id, "thumb|$path", 0) else PkgStore.key(p.id, "thumb|$path", size)

    suspend fun get(p: Ps4, path: String, size: Long, maxDim: Int = 160): Thumb? = withContext(Dispatchers.IO) {
        val key = thumbKey(p, path, size)
        fun hit(): Thumb? = PkgStore.bitmap(key, "icon0.png", maxDim)?.let { b -> val i = PkgStore.load(key); Thumb(b, i?.title, i?.titleId) }
        hit()?.let { return@withContext it }
        if (!PkgInspector.canRead(p) || size < PkgFormat.HEADER_MIN) return@withContext null
        if ((miss[key] ?: 0L) > System.currentTimeMillis()) return@withContext null
        PkgInspector.mutex.withLock {
            hit() ?: run {
                val r = PkgInspector.inspect(p, path, size, key, false)
                hit() ?: run { miss[key] = System.currentTimeMillis() + (if (r.notPkg) 1_800_000L else if (r.info != null) 25_000L else 90_000L); null }
            }
        }
    }

    /** Artwork of a download record: its own cache first, then the file on the PS4 (final file, else the .tmp). Adopts the result into the record. */
    suspend fun forDownload(d: Download, maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
        PkgStore.bitmap("d:${d.id}", "icon0.png", maxDim)?.let { return@withContext it }
        if (d.state == DlState.SUBMITTING || d.state == DlState.QUEUED || d.state == DlState.WAITING_FOR_START) return@withContext null
        val p = Ps4Repo.get(d.ps4Id) ?: return@withContext null
        val path = d.finalPath ?: d.tempPath ?: return@withContext null
        val t = get(p, path, d.currentSize, maxDim) ?: return@withContext null
        PkgStore.copy(thumbKey(p, path, d.currentSize), "d:${d.id}")
        DownloadRepo.update(d.id) { it.copy(iconReady = true, pkgTitle = it.pkgTitle ?: t.title, titleId = it.titleId ?: t.titleId) }
        t.bmp
    }
}
