package com.abdo.ps4monitor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

class UploadPlan(val uri: Uri, val name: String, val total: Long, val destDir: String, val existing: Long)

/**
 * Long, synchronous operations that ezRemote performs on the PS4 (install, copy, move, extract, upload, install from link).
 * They outlive the screen: they run in an app-level scope, keep the foreground service alive, and report through a toast + the Home events.
 * Only one runs at a time, because ezRemote refuses most of these while another activity is in progress.
 * Every result is verified by listing the folder again where ezRemote itself does not report the outcome (it ignores several return values).
 */
object Ops {
    private const val LONG = 3_600_000
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val active = MutableStateFlow(0)
    val label = MutableStateFlow("")
    val uploadPct = MutableStateFlow(-1)
    val refresh = MutableStateFlow(0)          // bumped when an operation ends: file lists reload
    @Volatile private var current: Job? = null
    fun cancel() { current?.cancel() }

    private fun toast(m: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(DownloadMonitor.app, m.take(400), Toast.LENGTH_LONG).show() } }

    fun launch(text: String, work: suspend () -> String): Boolean {
        if (active.value > 0) { toast(tr("Another operation is still running.", "عملية أخرى ما زالت جارية.")); return false }
        active.value = 1; label.value = text; uploadPct.value = -1
        DownloadMonitor.startSvc()
        current = scope.launch {
            val msg = try { work() }
                catch (e: CancellationException) { tr("Cancelled.", "أُلغيت العملية.") }
                catch (e: Exception) { Tx.t(PkgInspector.friendly(e)) }
            DownloadMonitor.d("Operation finished: $text -> ${msg.take(120)}")
            DownloadMonitor.ev("$text: ${msg.lineSequence().first().take(80)}")
            toast(msg)
            active.value = 0; label.value = ""; uploadPct.value = -1; refresh.update { it + 1 }
        }
        return true
    }

    private fun depth(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }.size
    private fun parent(path: String) = path.substringBeforeLast('/', "/").ifEmpty { "/" }
    private fun names(p: Ps4, dir: String): Set<String> = PkgInspector.browse(p, dir).map { it.name }.toSet()
    private fun fail(r: EzRemote.OpResult) = Tx.t(r.message)

    suspend fun install(p: Ps4, paths: List<String>): String = withContext(Dispatchers.IO) {
        val r = EzRemote.install(p, paths, 300_000)
        if (r.ok) tr("ezRemote accepted the install request. The PS4 shows the install progress itself; this app cannot track it.",
                     "قبل ezRemote طلب التثبيت. الـPS4 يعرض تقدم التثبيت بنفسه؛ التطبيق لا يستطيع تتبّعه.") else fail(r)
    }

    suspend fun installUrl(p: Ps4, url: String): String = withContext(Dispatchers.IO) {
        val r = EzRemote.installUrl(p, url, LONG)
        if (r.ok) tr("ezRemote accepted the install-from-link request. Watch the PS4 for the install progress.", "قبل ezRemote طلب التثبيت من الرابط. راقب الـPS4 لتقدم التثبيت.") else fail(r)
    }

    suspend fun mkdir(p: Ps4, path: String): String = withContext(Dispatchers.IO) {
        val r = EzRemote.createFolder(p, path)
        if (!r.ok) return@withContext fail(r)
        if (path.substringAfterLast('/') in names(p, parent(path))) tr("Folder created.", "أُنشئ المجلد.") else tr("The PS4 did not create the folder.", "لم ينشئ الـPS4 المجلد.")
    }

    suspend fun renameItem(p: Ps4, from: String, newName: String): String = withContext(Dispatchers.IO) {
        if (newName.isBlank() || '/' in newName) return@withContext tr("Invalid name.", "اسم غير صالح.")
        if (depth(from) < 2) return@withContext tr("Refusing to rename a top-level system folder.", "رفض إعادة تسمية مجلد نظام رئيسي.")
        val dir = parent(from)
        if (newName in names(p, dir)) return@withContext tr("An item named $newName already exists.", "يوجد عنصر باسم $newName بالفعل.")
        EzRemote.rename(p, from, joinPath(dir, newName), 30_000)      // ezRemote ignores the outcome: verified below
        val after = names(p, dir)
        if (newName in after && from.substringAfterLast('/') !in after) tr("Renamed to $newName.", "أُعيدت التسمية إلى $newName.") else tr("The PS4 did not rename it.", "لم يُعد الـPS4 تسميته.")
    }

    suspend fun copyMove(p: Ps4, items: List<Pair<String, FsEntry>>, destDir: String, move: Boolean): String = withContext(Dispatchers.IO) {
        val paths = items.map { joinPath(it.first, it.second.name) }
        if (move && paths.any { depth(it) < 2 }) return@withContext tr("Refusing to move a top-level system folder.", "رفض نقل مجلد نظام رئيسي.")
        for ((dir, e) in items) {
            val src = joinPath(dir, e.name)
            if (e.isDir && (destDir == src || destDir.startsWith("$src/"))) return@withContext tr("A folder cannot be copied or moved into itself.", "لا يمكن نسخ أو نقل مجلد إلى داخله.")
            if (dir == destDir) return@withContext tr("The destination is the same folder.", "الوجهة هي نفس المجلد.")
        }
        val r = if (move) EzRemote.move(p, paths, destDir, LONG) else EzRemote.copy(p, paths, destDir, LONG)
        val have = try { names(p, destDir) } catch (e: Exception) { emptySet() }
        val missing = items.filter { it.second.name !in have }
        when {
            missing.isEmpty() && r.ok -> tr(if (move) "Moved ${items.size} item(s)." else "Copied ${items.size} item(s).", if (move) "نُقل ${items.size} عنصر." else "نُسخ ${items.size} عنصر.")
            missing.isEmpty() -> tr("Done, but ezRemote reported: ", "تم، لكن ezRemote أبلغ: ") + fail(r)
            else -> tr("Not everything arrived: ${missing.joinToString { it.second.name }}. ", "لم يصل كل شيء: ${missing.joinToString { it.second.name }}. ") + (if (r.ok) "" else fail(r))
        }
    }

    suspend fun extract(p: Ps4, archive: String): String = withContext(Dispatchers.IO) {
        val dir = parent(archive); val name = archive.substringAfterLast('/'); val folder = name.substringBeforeLast('.', name).ifBlank { name + "_extracted" }
        val r = EzRemote.extract(p, archive, dir, folder, LONG)
        if (!r.ok) return@withContext fail(r)
        if (folder in names(p, dir)) tr("Extracted into the folder $folder.", "فُكّ الضغط في المجلد $folder.") else tr("ezRemote reported success but the folder was not found.", "أبلغ ezRemote بالنجاح لكن لم يوجد المجلد.")
    }

    suspend fun planUpload(p: Ps4, uri: Uri, destDir: String): UploadPlan = withContext(Dispatchers.IO) {
        val cr = DownloadMonitor.app.contentResolver
        var name = "upload.bin"; var size = -1L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size <= 0) throw IOException("Cannot read the size of that file")
        val safe = Names.clean(name).ifBlank { "upload.bin" }
        val existing = try { EzRemote.fileSize(p, joinPath(destDir, safe), 10_000) } catch (e: Exception) { 0L }
        UploadPlan(uri, safe, size, destDir, existing)
    }

    /** Chunked upload with resume. A retried chunk is only re-sent when the PS4 file size proves the previous attempt did not land. */
    suspend fun upload(p: Ps4, pl: UploadPlan, resume: Boolean): String = withContext(Dispatchers.IO) {
        val cr = DownloadMonitor.app.contentResolver
        val target = joinPath(pl.destDir, pl.name)
        val chunk = 8 * 1024 * 1024
        val buf = ByteArray(chunk)
        var sent = if (resume && pl.existing in 1 until pl.total) pl.existing else 0L
        var n = if (sent > 0) 1 else 0
        val ins = cr.openInputStream(pl.uri) ?: throw IOException("Cannot open the selected file")
        ins.use { s ->
            var skip = sent
            while (skip > 0) { val k = s.skip(skip); if (k > 0) skip -= k else if (s.read() < 0) break else skip-- }
            while (sent < pl.total) {
                currentCoroutineContext().ensureActive()
                val want = minOf(chunk.toLong(), pl.total - sent).toInt()
                var got = 0
                while (got < want) { val r = s.read(buf, got, want - got); if (r < 0) break; got += r }
                if (got <= 0) break
                var landed = false; var tries = 0
                while (!landed) {
                    tries++
                    try {
                        val r = EzRemote.uploadChunk(p, pl.destDir, pl.name, pl.total, if (sent == 0L) 0 else maxOf(n, 1), buf, got, 120_000)
                        if (!r.ok) throw IOException(r.message)
                        landed = true
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val rs = try { EzRemote.fileSize(p, target, 10_000) } catch (x: Exception) { -1L }
                        when {
                            rs == sent + got -> landed = true                     // the chunk arrived, only the answer was lost
                            rs == sent && tries < 3 -> { delay(1500) }          // nothing arrived: safe to send again
                            else -> throw IOException("Upload interrupted (PS4 has $rs of ${pl.total} bytes): ${e.message}")
                        }
                    }
                }
                sent += got; n++
                uploadPct.value = (sent * 100 / pl.total).toInt()
            }
        }
        val now = try { EzRemote.fileSize(p, target, 15_000) } catch (e: Exception) { -1L }
        if (now == pl.total) tr("Uploaded ${pl.name} (${Fmt.bytes(pl.total)}).", "تم رفع ${pl.name} (${Fmt.bytes(pl.total)}).")
        else tr("Upload incomplete: the PS4 has ${Fmt.bytes(maxOf(now, 0L))} of ${Fmt.bytes(pl.total)}. Check that the destination folder exists, then resume.",
                "الرفع غير مكتمل: لدى الـPS4 ${Fmt.bytes(maxOf(now, 0L))} من ${Fmt.bytes(pl.total)}. تأكد أن مجلد الوجهة موجود ثم استأنف.")
    }
}
