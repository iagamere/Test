package com.abdo.ps4monitor
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Shared monitoring/state layer. Sources of evidence (HttpFileMonitor = ezRemote /__local__/list, FtpFileMonitor = short FTP
 * sessions) only answer "what files exist in <dest> and how big are they". Download state is derived ONLY from that evidence.
 *   HTTP 200 from download_url  -> QUEUED (request accepted, nothing more)
 *   a matching .tmp appears      -> STARTING;  its size grows -> DOWNLOADING
 *   FTP/HTTP failures            -> PS4/link state, never "download failed"
 */
object DownloadMonitor {
    lateinit var app: Context
    val net = MutableStateFlow(true)
    val status = MutableStateFlow<Map<String, Ps4Status>>(emptyMap())
    val untracked = MutableStateFlow<Map<String, List<Pair<String, FsEntry>>>>(emptyMap())   // ps4Id -> (dir, file)
    val events = MutableStateFlow<List<String>>(emptyList())     // plain-language events for Home
    val log = MutableStateFlow<List<String>>(emptyList())        // advanced technical log
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val loops = ConcurrentHashMap<String, Job>()
    private val rts = ConcurrentHashMap<String, Rt>()
    private val pst = ConcurrentHashMap<String, P>()
    private val seenTmp = ConcurrentHashMap<String, Long>()
    @Volatile private var restored = false
    private const val NOT_STARTED_GRACE_MS = 30 * 60_000L     // keep watching a NOT_STARTED download for a late start
    private const val GIVE_UP_LOST_MS = 30 * 60_000L          // PS4 unreachable this long -> FAILED (still resumable by hand)

    private class P { var failStreak = 0; var firstFailAt = 0L; var lostSince = 0L; var httpRetryAt = 0L }
    private class Rt(d: Download) {
        var prevS = -1L; var prevT = 0L; val win = ArrayDeque<Pair<Long, Long>>()
        var peak = d.peakSpeed; var ema = 0.0; var speeds = listOf<Float>()
        var lastGrow = 0L; var firstT = 0L; var firstS = 0L; var lastSize = d.currentSize
        var grew = d.startedAt > 0; var unchanged = 0; var unobserved = 0L
        var absent = 0; var absentSince = 0L; var ambLogged = false
        var finalSize = -1L; var finalStable = 0; var finalSince = 0L; var finalAbsent = 0; var expStableSince = 0L
    }

    // ---------------- logging ----------------
    private var lastMsg = ""; private var rep = 0
    private fun stamp() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    /** Technical log (advanced). Consecutive identical lines are collapsed. Never pass credentials here. */
    fun d(m: String) {
        synchronized(this) {
            if (m == lastMsg) { rep++; return }
            if (rep > 0) log.update { (it + "[${stamp()}] (previous line repeated $rep more times)").takeLast(400) }
            rep = 0; lastMsg = m
            log.update { (it + "[${stamp()}] $m").takeLast(400) }
        }
    }
    fun clearLog() { log.value = emptyList() }
    private fun ev(m: String) = events.update { (it + "${Fmt.time(System.currentTimeMillis())}  $m").takeLast(40) }

    fun init(c: Context) { app = c.applicationContext }
    fun kick() { wake.tryEmit(Unit) }
    private fun startSvc() { runCatching { ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java)) } }
    fun norm(p: String): String { val t = p.trim().trimEnd('/'); return if (t.isEmpty()) "/" else if (t.startsWith("/")) t else "/$t" }
    private fun upStatus(id: String, f: (Ps4Status) -> Ps4Status) = status.update { m -> m + (id to f(m[id] ?: Ps4Status())) }
    fun isWatch(d: Download, now: Long = System.currentTimeMillis()) =
        (d.state.active && d.state != DlState.SUBMITTING) ||
        (d.state == DlState.NOT_STARTED && !d.superseded && d.tempPath == null && now - d.submittedAt < NOT_STARTED_GRACE_MS)
    fun watched(ps4Id: String) = DownloadRepo.all.value.filter { it.ps4Id == ps4Id && isWatch(it) }
    fun anyWatched() = DownloadRepo.all.value.any { isWatch(it) }

    // ---------------- restart recovery ----------------
    /** Loads nothing new (repository already loaded); re-attaches monitoring. Never re-sends a request. */
    fun restore() {
        if (restored) { ensureAll(); return }
        restored = true
        DownloadRepo.all.value.filter { it.state == DlState.SUBMITTING }.forEach {
            DownloadRepo.update(it.id) { x -> x.copy(state = DlState.FAILED, errorMessage = "The app closed while sending. The request was NOT repeated; check the PS4 or retry.", note = "") }
        }
        DownloadRepo.all.value.filter { it.state.active }.forEach { DownloadRepo.update(it.id) { x -> x.copy(speed = 0.0, avgSpeed = 0.0, etaSec = -1) } }
        val n = DownloadRepo.all.value.count { isWatch(it) }
        if (n > 0) { d("Restored $n active download(s); re-attaching monitoring"); ensureAll(); startSvc() }
    }
    private fun ensureAll() = DownloadRepo.all.value.filter { isWatch(it) }.map { it.ps4Id }.distinct().forEach { ensureLoop(it) }

    private fun ensureLoop(ps4Id: String) {
        loops.compute(ps4Id) { _, j ->
            if (j != null && j.isActive) j else scope.launch {
                val me = currentCoroutineContext()[Job]
                try { loop(ps4Id) } catch (e: CancellationException) { throw e } catch (e: Exception) { d("Monitor loop error: ${e.javaClass.simpleName}: ${e.message}") }
                finally { if (me != null) loops.remove(ps4Id, me); if (watched(ps4Id).isNotEmpty()) ensureLoop(ps4Id) }
            }
        }
    }

    // ---------------- public actions ----------------
    private fun nameFromUrl(u: String) = runCatching {
        Uri.decode(u.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')).ifBlank { null }
            ?: Uri.parse(u).host ?: "download"
    }.getOrDefault("download")

    suspend fun submit(ps4: Ps4, url: String, destIn: String, manualSize: Long = 0L, retryOf: Download? = null, fileName: String? = null, sendName: Boolean = false): SubmitResult =
        withContext(Dispatchers.IO) {
            val u = url.trim()
            if (!Regex("^https?://\\S+$").matches(u)) return@withContext SubmitResult.Invalid("That is not a valid http(s) link.")
            if (ps4.host.isBlank()) return@withContext SubmitResult.Invalid("This PS4 has no IP address yet.")
            val dest = norm(destIn.ifBlank { ps4.dest })
            if (retryOf == null) DownloadRepo.all.value.firstOrNull {
                it.ps4Id == ps4.id && it.sourceUrl == u && norm(it.dest) == dest && it.state.active && !it.superseded
            }?.let { return@withContext SubmitResult.Duplicate("This link is already being downloaded to ${ps4.name}.") }
            val fname = fileName?.let { Names.clean(it) }?.takeIf { it.isNotEmpty() }
            val destSend = if (sendName && fname != null) dest.trimEnd('/') + "/" + fname else dest      // full path only when the user kept "send name" on
            val s = Store.settings()
            val now = System.currentTimeMillis()
            val base = fetchList(ps4, dest, s)?.associate { it.name to it.size }       // files that exist BEFORE submission
            if (base == null) d("Could not snapshot $dest before submitting; file matching will need name evidence")
            val id = UUID.randomUUID().toString()
            val keep = retryOf?.takeIf { it.expectedSource.startsWith("entered") }
            DownloadRepo.add(Download(id, ps4.id, (retryOf?.attempt ?: 0) + 1, retryOf?.id, u, fname ?: nameFromUrl(u), dest,
                expectedSize = manualSize.takeIf { it > 0 } ?: keep?.expectedSize,
                expectedSource = if (manualSize > 0) "entered by you" else if (keep != null) "entered by you" else "",
                state = DlState.SUBMITTING, createdAt = now, submittedAt = now, notificationId = Store.nextNotifId(), baseline = base, fileName = fname))
            d("Download request submitted (${ps4.name}, dest=$destSend)")
            when (val r = EzRemote.submit(ps4, u, destSend)) {
                is EzRemote.Submit.Rejected -> { DownloadRepo.remove(id); d("State: rejected — ${r.message}"); SubmitResult.Rejected(r.message) }
                is EzRemote.Submit.Unreachable -> { DownloadRepo.remove(id); d("State: not sent — ${r.message}"); SubmitResult.Unreachable(r.message) }
                is EzRemote.Submit.Accepted -> {
                    val t = System.currentTimeMillis()
                    DownloadRepo.update(id) { it.copy(state = DlState.QUEUED, submittedAt = t, note = "ezRemote accepted the request. Waiting for the PS4 to start.") }
                    d("ezRemote success=true"); d("State: QUEUED (request accepted — NOT proof that the download started)")
                    ev("${nameFromUrl(u)}: sent to ${ps4.name}")
                    ensureLoop(ps4.id); startSvc(); syncNotifs()
                    if (manualSize <= 0 && retryOf?.expectedSize == null) scope.launch {
                        val info = Net.size(u)
                        if (info != null && !info.uncertain && info.note.startsWith("confirmed")) {
                            DownloadRepo.update(id) { x -> if (x.expectedSize == null) x.copy(expectedSize = info.bytes, expectedSource = "server size, ${info.note}") else x }
                            d("Expected size from server: ${Fmt.bytes(info.bytes)}")
                        } else d("Expected size unknown (${info?.note ?: "server did not report a size"})")
                    }
                    SubmitResult.Accepted(id)
                }
            }
        }

    /** Explicit user action: a NEW attempt. The old record is kept and marked superseded. Never automatic. */
    suspend fun retry(id: String): SubmitResult {
        val old = DownloadRepo.get(id) ?: return SubmitResult.Invalid("Download not found.")
        val p = Ps4Repo.get(old.ps4Id) ?: return SubmitResult.Invalid("That PS4 profile no longer exists.")
        if (old.sourceUrl.isBlank()) return SubmitResult.Invalid("This download was detected on the PS4, so there is no link to resend.")
        if (old.state !in setOf(DlState.NOT_STARTED, DlState.FAILED, DlState.STOPPED)) return SubmitResult.Invalid("Retry is only available for failed or not-started downloads.")
        val r = submit(p, old.sourceUrl, old.dest, 0L, old, old.fileName, old.fileName != null && Store.sp.getBoolean("sendpath", true))
        if (r is SubmitResult.Accepted) { DownloadRepo.update(id) { it.copy(superseded = true) }; d("Retry created attempt ${old.attempt + 1}") }
        return r
    }

    /** Re-attach monitoring to an existing record WITHOUT sending anything to ezRemote. */
    fun resume(id: String) {
        val x = DownloadRepo.get(id) ?: return
        if (x.superseded || x.state.active) return
        rts.remove(id)
        DownloadRepo.update(id) { it.copy(state = if (it.tempPath != null) DlState.STARTING else DlState.WAITING_FOR_START,
            note = "Monitoring resumed (nothing was sent to ezRemote).", errorMessage = null, terminalNotified = false,
            submittedAt = if (it.tempPath == null) System.currentTimeMillis() else it.submittedAt, completedAt = 0) }
        d("${x.displayName}: monitoring resumed by user"); ensureLoop(x.ps4Id); startSvc()
    }
    fun stop(id: String) {
        val x = DownloadRepo.get(id) ?: return
        DownloadRepo.update(id) { it.copy(state = DlState.STOPPED, note = "Monitoring stopped by you. The PS4 download itself is not cancelled.", speed = 0.0, etaSec = -1) }
        d("${x.displayName}: monitoring stopped by user"); syncNotifs()
    }
    fun remove(id: String) { Notifier.cancel(DownloadRepo.get(id)?.notificationId ?: return); rts.remove(id); pps.remove(id); PkgStore.delete("d:$id"); DownloadRepo.remove(id) }
    fun setExpected(id: String, bytes: Long) {
        DownloadRepo.update(id) { it.copy(expectedSize = bytes, expectedSource = "entered by you") }; d("Expected size entered by user: ${Fmt.bytes(bytes)}")
    }
    /** User resolves an ambiguous match explicitly. */
    fun assignFile(id: String, fileName: String) {
        val x = DownloadRepo.get(id) ?: return
        DownloadRepo.update(id) { it.copy(tempPath = "${norm(it.dest)}/$fileName", state = DlState.STARTING, note = "File chosen by you.", errorMessage = null, terminalNotified = false) }
        d("${x.displayName}: file chosen by user: $fileName"); ensureLoop(x.ps4Id); startSvc()
    }
    /** Monitor a .tmp that was started elsewhere (e.g. directly in ezRemote). */
    fun adopt(ps4Id: String, dir: String, e: FsEntry) {
        if (DownloadRepo.all.value.any { it.ps4Id == ps4Id && it.state.active && it.tempPath == "${norm(dir)}/${e.name}" }) return
        val now = System.currentTimeMillis()
        DownloadRepo.add(Download(UUID.randomUUID().toString(), ps4Id, 1, null, "", e.name.removeSuffix(".tmp"), norm(dir),
            tempPath = "${norm(dir)}/${e.name}", currentSize = e.size, state = DlState.STARTING, note = "Detected on the PS4 (not started from this app).",
            createdAt = now, submittedAt = now, notificationId = Store.nextNotifId()))
        d("Detected download file: ${e.name} (adopted)"); ev("Now monitoring ${e.name}")
        untracked.update { m -> m + (ps4Id to m[ps4Id].orEmpty().filter { it.second.name != e.name }) }
        ensureLoop(ps4Id); startSvc()
    }

    /** Explicit connectivity check for the UI. Tests HTTP and FTP separately and refreshes the untracked-.tmp list. */
    suspend fun probe(p: Ps4): String = withContext(Dispatchers.IO) {
        val s = Store.settings(); val dir = norm(p.dest)
        var httpOk = false; var ftpOk = false; var files: List<FsEntry>? = null
        val parts = mutableListOf<String>()
        if (p.mode != MonitorMode.FTP_ONLY) {
            try { files = EzRemote.list(p, dir, s.timeout * 1000).filter { !it.isDir }; httpOk = true; parts += "ezRemote web: OK" }
            catch (e: Exception) { d("Probe HTTP: ${e.javaClass.simpleName}: ${e.message}"); parts += "ezRemote web: " + EzRemote.friendly(e) }
        }
        if (p.mode != MonitorMode.HTTP_ONLY && p.ftpPort > 0) {
            try { val l = Ftp.listOnce(Conn(p.host, p.ftpPort, p.ftpUser, p.ftpPass), s, dir).filter { !it.isDir }; ftpOk = true; if (files == null) files = l; parts += "FTP: OK" }
            catch (e: SoftError) { ftpOk = true; parts += "FTP: connected, but ${e.message}" }
            catch (e: Exception) { d("Probe FTP: ${e.javaClass.simpleName}: ${e.message}"); parts += "FTP: " + Ftp.friendly(e) }
        }
        val any = httpOk || ftpOk
        upStatus(p.id) { it.copy(
            http = if (p.mode == MonitorMode.FTP_ONLY) Link.DISABLED else if (httpOk) Link.AVAILABLE else Link.UNAVAILABLE,
            ftp = if (p.mode == MonitorMode.HTTP_ONLY || p.ftpPort <= 0) Link.DISABLED else if (ftpOk) Link.AVAILABLE else Link.UNAVAILABLE,
            reach = if (any) Reach.REACHABLE else Reach.UNREACHABLE, lastOkAt = if (any) System.currentTimeMillis() else it.lastOkAt) }
        files?.let { updateUntracked(p, dir, it) }
        parts.joinToString("\n")
    }

    // ---------------- listing sources (HttpFileMonitor + FtpFileMonitor) ----------------
    private fun ftpOn(p: Ps4) = p.mode != MonitorMode.HTTP_ONLY && p.ftpPort > 0
    /** HTTP first (ezRemote /__local__/list); FTP is the fallback. Returns null only if every enabled source failed. */
    private fun fetchList(p: Ps4, dir: String, s: Settings): List<FsEntry>? {
        val st = pst.getOrPut(p.id) { P() }; val now = System.currentTimeMillis()
        if (p.mode != MonitorMode.FTP_ONLY && (now >= st.httpRetryAt)) {
            try {
                val l = EzRemote.list(p, dir, s.timeout * 1000).filter { !it.isDir }
                upStatus(p.id) { it.copy(http = Link.AVAILABLE, ftp = if (ftpOn(p)) Link.STANDBY else Link.DISABLED) }
                return l
            } catch (e: Exception) {
                d("HTTP list failed: ${e.javaClass.simpleName}: ${e.message}")      // raw detail stays in the advanced log
                upStatus(p.id) { it.copy(http = Link.UNAVAILABLE) }
                if (ftpOn(p)) st.httpRetryAt = now + 30_000                          // do not hammer a dead HTTP endpoint while FTP works
            }
        }
        if (ftpOn(p)) {
            if (status.value[p.id]?.ftp == Link.UNAVAILABLE) upStatus(p.id) { it.copy(ftp = Link.RECONNECTING) }
            try {
                val l = Ftp.listOnce(Conn(p.host, p.ftpPort, p.ftpUser, p.ftpPass), s, dir).filter { !it.isDir }   // short-lived session
                upStatus(p.id) { it.copy(ftp = Link.AVAILABLE, http = if (p.mode == MonitorMode.FTP_ONLY) Link.DISABLED else it.http) }
                return l
            } catch (e: SoftError) {
                d("FTP: ${e.message}"); upStatus(p.id) { it.copy(ftp = Link.AVAILABLE) }; return emptyList()    // folder not created yet
            } catch (e: Exception) {
                d("FTP list failed: ${e.javaClass.simpleName}: ${e.message}"); upStatus(p.id) { it.copy(ftp = Link.UNAVAILABLE) }
            }
        }
        return null
    }

    // ---------------- per-PS4 loop ----------------
    private suspend fun loop(ps4Id: String) {
        val st = pst.getOrPut(ps4Id) { P() }
        while (currentCoroutineContext().isActive) {
            val p = Ps4Repo.get(ps4Id) ?: return
            val ds = watched(ps4Id)
            if (ds.isEmpty()) return
            val s = Store.settings()
            val listings = HashMap<String, List<FsEntry>>(); var failed = false
            for (dir in ds.map { norm(it.dest) }.distinct()) {
                val l = fetchList(p, dir, s); if (l == null) { failed = true; break }; listings[dir] = l
            }
            var wait = s.interval * 1000L
            if (failed) { onFailure(p, st, s); wait = maxOf(wait, minOf(s.interval * 1000L shl minOf(st.failStreak, 4), 30_000L)) }
            else {
                onSuccess(p, st)
                try { process(p, listings, s) } catch (e: CancellationException) { throw e } catch (e: Exception) { d("Process error: ${e.javaClass.simpleName}: ${e.message}") }
            }
            if (watched(ps4Id).all { it.state == DlState.NOT_STARTED }) wait = maxOf(wait, 10_000L)
            syncNotifs()
            withTimeoutOrNull(wait) { wake.first() }          // a network callback can wake us early
        }
    }

    private fun onSuccess(p: Ps4, st: P) {
        val now = System.currentTimeMillis()
        if (st.firstFailAt > 0) {
            val outage = now - st.firstFailAt
            watched(p.id).forEach { dl -> rts[dl.id]?.let { r -> r.unobserved += outage; r.prevS = -1; r.win.clear() } }   // no fake speed after a gap
            if (st.lostSince > 0) { d("PS4 reachable again after ${outage / 1000}s"); ev("${p.name}: connection restored"); Notifier.connection(p, false) }
        }
        st.failStreak = 0; st.firstFailAt = 0; st.lostSince = 0
        upStatus(p.id) { it.copy(reach = Reach.REACHABLE, lastOkAt = now, message = "") }
    }

    private fun onFailure(p: Ps4, st: P, s: Settings) {
        val now = System.currentTimeMillis()
        st.failStreak++; if (st.firstFailAt == 0L) st.firstFailAt = now
        upStatus(p.id) { it.copy(message = "PS4 monitoring connection interrupted. Reconnecting…") }
        if (st.failStreak >= 3 && st.lostSince == 0L) {
            st.lostSince = now
            upStatus(p.id) { it.copy(reach = Reach.UNREACHABLE) }
            watched(p.id).filter { it.state != DlState.NOT_STARTED }.forEach { transition(it, DlState.CONNECTION_LOST, "PS4 monitoring connection interrupted. Reconnecting…") }
            d("PS4 unreachable after ${st.failStreak} failed polls (downloads are NOT marked failed)")
            ev("${p.name}: connection lost, reconnecting…"); Notifier.connection(p, true)
        }
        if (st.lostSince > 0 && now - st.lostSince > GIVE_UP_LOST_MS) {
            watched(p.id).filter { it.state.active }.forEach { dl ->
                transition(dl, DlState.FAILED, "", "PS4 connection lost"); d("${dl.displayName}: FAILED — PS4 unreachable for ${GIVE_UP_LOST_MS / 60000} min (use Resume monitoring when it is back)") }
        }
    }

    private fun transition(d0: Download, st: DlState, note: String, err: String? = null, f: (Download) -> Download = { it }) {
        val old = DownloadRepo.get(d0.id) ?: return
        if (old.state != st) d("${old.displayName}: ${old.state} → $st${if (note.isNotBlank()) " ($note)" else ""}")
        DownloadRepo.update(old.id) { f(it).copy(state = st, note = note, errorMessage = if (st == DlState.FAILED || st == DlState.NOT_STARTED) (err ?: it.errorMessage) else null,
            speed = if (st.active && st != DlState.CONNECTION_LOST) it.speed else 0.0, etaSec = if (st.active && st != DlState.CONNECTION_LOST) it.etaSec else -1) }
    }

    // ---------------- association + state machine ----------------
    private fun process(p: Ps4, listings: Map<String, List<FsEntry>>, s: Settings) {
        val now = System.currentTimeMillis()
        for ((dir, entries) in listings) {
            val dls = watched(p.id).filter { norm(it.dest) == dir }
            associate(p, dir, entries, dls)
            updateUntracked(p, dir, entries)
            for (d0 in dls) { val x = DownloadRepo.get(d0.id) ?: continue; if (isWatch(x, now)) step(p, x, entries, s, now) }
        }
    }

    private fun isTmp(n: String) = n.endsWith(".tmp", true)
    private fun stem(n: String) = n.substringBeforeLast('.', n).removeSuffix(".pkg").lowercase()
    private fun related(a: String, b: String): Boolean { val x = stem(a); val y = stem(b); return x.length >= 4 && y.length >= 4 && (x.contains(y) || y.contains(x)) }
    private fun claimedTmp(p: Ps4, dir: String) = DownloadRepo.all.value.filter { it.ps4Id == p.id && norm(it.dest) == dir && it.state.active && it.tempPath != null }
        .map { it.tempPath!!.substringAfterLast('/') }.toSet()

    /** Match a submitted download to a .tmp using evidence only. Ambiguity is never resolved by guessing. */
    private fun associate(p: Ps4, dir: String, entries: List<FsEntry>, dls: List<Download>) {
        val pending = dls.mapNotNull { DownloadRepo.get(it.id) }.filter {
            it.tempPath == null && it.sourceUrl.isNotBlank() && (it.state == DlState.QUEUED || it.state == DlState.WAITING_FOR_START || it.state == DlState.NOT_STARTED || it.state == DlState.CONNECTION_LOST)
        }.sortedBy { it.submittedAt }
        if (pending.isEmpty()) return
        val tmps = entries.filter { isTmp(it.name) && it.name !in claimedTmp(p, dir) }.toMutableList()
        if (tmps.isEmpty()) return
        var remaining = pending.size
        for (dl in pending) {
            val cands = tmps.filter { e -> dl.baseline?.containsKey(e.name) != true }       // new since this request was submitted
            if (cands.isEmpty()) { remaining--; continue }
            val nameHits = cands.filter { related(it.name, dl.displayName) }
            val pick: Pair<FsEntry, String>? = when {
                nameHits.size == 1 -> nameHits[0] to "file name matches the link"
                dl.baseline != null && cands.size == 1 && remaining == 1 -> cands[0] to "the only new .tmp since submission and the only pending download"
                else -> null
            }
            if (pick == null) {
                val rt = rts.getOrPut(dl.id) { Rt(dl) }
                if (!rt.ambLogged) { rt.ambLogged = true; d("${dl.displayName}: ${cands.size} new .tmp file(s) but $remaining pending download(s); not guessing (choose the file manually in the download's details)") }
                continue
            }
            tmps.remove(pick.first); remaining--
            DownloadRepo.update(dl.id) { it.copy(tempPath = "$dir/${pick.first.name}") }
            d("Detected download file: ${pick.first.name} (${pick.second})")
            transition(dl, DlState.STARTING, "Download file detected", f = { it.copy(tempPath = "$dir/${pick.first.name}") })
        }
    }

    /** Candidate file list for the UI (manual matching) and the "Monitor" button. */
    private fun updateUntracked(p: Ps4, dir: String, entries: List<FsEntry>) {
        val claimed = claimedTmp(p, dir) + DownloadRepo.all.value.filter { it.ps4Id == p.id && it.state == DlState.STOPPED && it.tempPath != null && norm(it.dest) == dir }
            .map { it.tempPath!!.substringAfterLast('/') }          // the user explicitly stopped these: do not offer them again
        val left = entries.filter { isTmp(it.name) && it.name !in claimed }
        val auto = Store.settings().auto
        val pendingHere = DownloadRepo.all.value.any { it.ps4Id == p.id && norm(it.dest) == dir && it.tempPath == null && it.sourceUrl.isNotBlank() && isWatch(it) }
        left.forEach { e ->                           // auto-adopt only a .tmp that is provably growing and not explainable by a pending request
            val key = "${p.id}|$dir/${e.name}"; val prev = seenTmp.put(key, e.size)
            if (auto && !pendingHere && prev != null && e.size > prev) adopt(p.id, dir, e)
        }
        untracked.update { m -> m + (p.id to (m[p.id].orEmpty().filter { it.first != dir } + left.map { dir to it })) }
    }

    private fun step(p: Ps4, d0: Download, entries: List<FsEntry>, s: Settings, now: Long) {
        val rt = rts.getOrPut(d0.id) { Rt(d0) }
        val dir = norm(d0.dest)
        val byName = entries.associateBy { it.name }

        if (d0.tempPath == null) {                          // accepted, but no file evidence yet
            val waited = now - d0.submittedAt - rt.unobserved
            when {
                d0.state == DlState.QUEUED || d0.state == DlState.CONNECTION_LOST -> transition(d0, DlState.WAITING_FOR_START, "Waiting for PS4 download to start…")
                d0.state == DlState.WAITING_FOR_START && waited >= s.notStarted * 1000L -> {
                    transition(d0, DlState.NOT_STARTED, "ezRemote accepted the request but no download activity was detected.",
                        "ezRemote accepted the request but no download activity was detected.")
                    d("${d0.displayName}: no .tmp appeared in ${waited / 1000}s; NOT resubmitting (manual Retry available)")
                    ev("${d0.displayName}: download has not started")
                }
            }
            return
        }
        val tmpName = d0.tempPath.substringAfterLast('/')
        if (d0.finalPath != null) { verifyFinal(p, d0, rt, byName, s, now); return }
        val e = byName[tmpName]

        if (e == null) {                                    // temp file gone: finished? cancelled? renamed?
            if (d0.state == DlState.NOT_STARTED) return
            rt.absent++; if (rt.absent == 1) rt.absentSince = now
            if (rt.absent < 2) { transition(d0, DlState.VERIFYING, "Temporary file disappeared — confirming…"); return }
            val finals = entries.filter { !isTmp(it.name) && (d0.baseline?.get(it.name) != it.size) &&
                DownloadRepo.all.value.none { o -> o.id != d0.id && o.ps4Id == p.id && o.finalPath == "$dir/${it.name}" } }
            val last = maxOf(rt.lastSize, d0.currentSize)
            val stemHits = finals.filter { related(it.name, tmpName) || related(it.name, d0.displayName) }
            val sizeHits = finals.filter { abs(it.size - last) <= maxOf(last / 100, 1L shl 20) }
            val pick = stemHits.singleOrNull() ?: stemHits.filter { it in sizeHits }.singleOrNull() ?: if (stemHits.isEmpty()) sizeHits.singleOrNull() else null
            val gone = now - rt.absentSince
            when {
                pick != null -> { d("Temporary file replaced by ${pick.name} (${Fmt.bytes(pick.size)})")
                    rt.finalSize = -1; rt.finalStable = 0; rt.finalAbsent = 0
                    transition(d0, DlState.VERIFYING, "Checking downloaded file…", f = { it.copy(finalPath = "$dir/${pick.name}", currentSize = pick.size) }) }
                finals.isEmpty() && gone >= 30_000 && !rt.grew -> transition(d0, DlState.FAILED, "", "The download file disappeared before any data was seen.")
                finals.isEmpty() && gone >= 30_000 -> transition(d0, DlState.FAILED, "", "The temporary file disappeared and no finished file was found (the download may have been cancelled on the PS4).")
                finals.isNotEmpty() && gone >= 120_000 -> transition(d0, DlState.FAILED, "", "The temporary file disappeared but the finished file could not be identified.")
                else -> transition(d0, DlState.VERIFYING, "Looking for the finished file…")
            }
            return
        }

        rt.absent = 0
        val size = e.size
        maybeProbe(p, d0, d0.tempPath, size, now)
        if (rt.firstT == 0L) { rt.firstT = now; rt.firstS = size; rt.lastGrow = now }
        if (rt.prevS >= 0 && size < rt.prevS) { rt.win.clear(); rt.prevS = -1; rt.lastGrow = now; d("${d0.displayName}: file size decreased; treating as restarted") }
        var cur = 0.0; var firstGrowth = false
        if (rt.prevS >= 0 && now > rt.prevT) {
            cur = (size - rt.prevS) * 1000.0 / (now - rt.prevT)               // bytes added / seconds elapsed — filesystem evidence only
            if (size > rt.prevS) { rt.lastGrow = now; rt.unchanged = 0; rt.expStableSince = 0; if (!rt.grew) { rt.grew = true; firstGrowth = true } } else rt.unchanged++
        }
        rt.win.addLast(now to size)
        while (rt.win.size > 2 && now - rt.win.first().first > 30_000) rt.win.removeFirst()
        val avg = if (rt.win.size >= 2 && now > rt.win.first().first) (size - rt.win.first().second) * 1000.0 / (now - rt.win.first().first) else 0.0
        rt.ema = if (rt.prevS < 0) cur else rt.ema * 0.7 + cur * 0.3
        if (cur > rt.peak) rt.peak = cur
        rt.speeds = (rt.speeds + cur.toFloat()).takeLast(maxOf(30, 300 / s.interval))
        rt.lastSize = size
        val exp = d0.expectedSize?.takeIf { it > 0 }
        val margin = if (d0.expectedSource.contains("confirmed") || d0.expectedSource.contains("agrees")) (1L shl 16) else maxOf(1L shl 20, (exp ?: 0L) / 1000)
        val eta = if (exp != null && size < exp && avg > 1.0) ((exp - size) / avg).toLong() else -1L      // never a fake ETA
        val atExp = exp != null && size >= exp - margin

        val st: DlState; val note: String
        val stalledMs = now - rt.lastGrow
        if (atExp) { st = DlState.VERIFYING; note = "Expected size reached — waiting for the PS4 to finish the file…"
            if (rt.unchanged >= 3 && rt.expStableSince == 0L) rt.expStableSince = now }
        else if (rt.unchanged >= 3 && stalledMs >= s.stuck * 1000L) { st = DlState.STALLED; note = "No download progress detected for ${stalledMs / 1000} s." }
        else if (rt.grew) { st = DlState.DOWNLOADING; note = "" }
        else { st = DlState.STARTING; note = "File detected; waiting for data…" }

        val old = DownloadRepo.get(d0.id) ?: return
        if (old.state != st) {
            d("${old.displayName}: ${old.state} → $st  size=${Fmt.bytes(size)}")
            if (st == DlState.STALLED) ev("${old.displayName}: stalled")
            if (old.state == DlState.STALLED && st == DlState.DOWNLOADING) ev("${old.displayName}: progress resumed")
        }
        if (firstGrowth) { d("Detected download progress: ${old.displayName} ${Fmt.bytes(size)}"); ev("${old.displayName}: download started") }
        DownloadRepo.update(old.id) { it.copy(state = st, note = note, currentSize = size, speed = rt.ema, avgSpeed = avg, peakSpeed = rt.peak, etaSec = eta,
            speeds = rt.speeds, lastSeenAt = now, startedAt = if (firstGrowth && it.startedAt == 0L) now else it.startedAt, errorMessage = null) }
        rt.prevS = size; rt.prevT = now
        if (atExp && rt.expStableSince > 0 && now - rt.expStableSince >= 120_000) {
            complete(old, size, "Reached the expected size and stayed stable (the PS4 kept the temporary file name).")
        }
    }

    private fun verifyFinal(p: Ps4, d0: Download, rt: Rt, byName: Map<String, FsEntry>, s: Settings, now: Long) {
        val e = byName[d0.finalPath!!.substringAfterLast('/')]
        if (e == null) {
            rt.finalAbsent++
            if (rt.finalAbsent >= 3) transition(d0, DlState.FAILED, "", "The finished file disappeared while it was being checked.")
            return
        }
        rt.finalAbsent = 0
        maybeProbe(p, d0, d0.finalPath, e.size, now)
        if (e.size != rt.finalSize) { rt.finalSize = e.size; rt.finalStable = 0; rt.finalSince = now } else rt.finalStable++
        val stable = rt.finalStable >= 3 && now - rt.finalSince >= maxOf(10_000L, 3000L * s.interval)
        DownloadRepo.update(d0.id) { it.copy(currentSize = e.size, lastSeenAt = now) }
        if (!stable) { transition(d0, DlState.VERIFYING, "Checking downloaded file…"); return }
        val exp = d0.expectedSize?.takeIf { it > 0 }
        val margin = if (d0.expectedSource.contains("confirmed") || d0.expectedSource.contains("agrees")) (1L shl 16) else maxOf(1L shl 20, (exp ?: 0L) / 1000)
        when {
            exp == null -> complete(d0, e.size, "The finished file is stable. Expected size was unknown, so this was verified by file lifecycle only.")
            abs(e.size - exp) <= margin -> complete(d0, e.size, "The finished file matches the expected size.")
            else -> transition(d0, DlState.FAILED, "", "The finished file is ${Fmt.bytes(e.size)} but ${Fmt.bytes(exp)} was expected.")
        }
    }

    private fun complete(d0: Download, size: Long, why: String) {
        transition(d0, DlState.COMPLETED, why, f = { it.copy(currentSize = size, completedAt = System.currentTimeMillis(), speed = 0.0, etaSec = -1) })
        ev("${d0.displayName}: completed")
    }

    /** Deletes the file(s) of a download from the PS4 (FTP DELE). Optionally removes the record too. Returns a message for the user. */
    suspend fun deleteFromPs4(id: String, removeRecord: Boolean): String = withContext(Dispatchers.IO) {
        val dl = DownloadRepo.get(id) ?: return@withContext tr("Download not found.", "التحميل غير موجود.")
        val p = Ps4Repo.get(dl.ps4Id) ?: return@withContext tr("That PS4 profile no longer exists.", "ملف هذا الـPS4 لم يعد موجودًا.")
        val paths = listOfNotNull(dl.tempPath, dl.finalPath).distinct()
        if (paths.isEmpty()) {
            if (removeRecord) remove(id)
            return@withContext tr("No file on the PS4 is linked to this download yet, so only the record was removed.", "لا يوجد ملف على الـPS4 مرتبط بهذا التحميل بعد، لذلك حُذف السجل فقط.")
        }
        val wasActive = dl.state.active
        if (wasActive) stop(id)                     // so the vanishing file is not reported as a failure
        val out = try { Remote.delete(p, paths.map { Remote.Item(it, false) }, if (wasActive) 5000L else 0L) }
                  catch (e: Exception) { if (wasActive) resume(id); return@withContext Tx.t(PkgInspector.friendly(e)) }
        if (out.deleted.isEmpty() && wasActive) resume(id)
        val parts = ArrayList<String>(); parts += Remote.summary(out)
        if (wasActive && out.deleted.isNotEmpty()) parts += tr("If ezRemote still has the file open it may keep transferring data in the background: this app has no confirmed way to cancel that transfer.",
            "إذا كان ezRemote ما زال يفتح الملف فقد يواصل نقل البيانات في الخلفية: لا يملك التطبيق وسيلة مؤكدة لإلغاء هذا النقل.")
        if (removeRecord && out.failed.isEmpty() && out.reappeared.isEmpty()) remove(id)
        else DownloadRepo.update(id) { it.copy(note = Remote.summary(out)) }
        parts.joinToString("\n")
    }

    // ---------------- PKG probe: title, icon and total size read from the PKG header (FTP, short sessions) ----------------
    private class PP { var attempts = 0; var nextAt = 0L; var done = false; @Volatile var running = false }
    private val pps = ConcurrentHashMap<String, PP>()

    private fun maybeProbe(p: Ps4, dl: Download, path: String, size: Long, now: Long) {
        if (!PkgInspector.ftpOn(p) || size < PkgFormat.HEADER_MIN) return
        val st = pps.getOrPut(dl.id) { PP() }
        if (st.done || st.running || now < st.nextAt) return
        st.running = true; st.attempts++
        scope.launch {
            try {
                val r = PkgInspector.mutex.let { m -> m.lock(); try { PkgInspector.inspect(p, path, size, "d:${dl.id}", full = false) } finally { m.unlock() } }
                applyProbe(dl, r, st, System.currentTimeMillis())
            } catch (e: CancellationException) { throw e } catch (e: Exception) { d("PKG probe error: ${e.javaClass.simpleName}: ${e.message}"); st.nextAt = System.currentTimeMillis() + 60_000
            } finally { st.running = false }
        }
    }
    private fun applyProbe(dl: Download, r: PkgInspector.Result, st: PP, now: Long) {
        val info = r.info
        when {
            r.notPkg -> { st.done = true; d("PKG probe: ${dl.displayName} is not a PS4 PKG (no further probing)") }
            info != null -> {
                DownloadRepo.update(dl.id) { x ->
                    var y = x.copy(pkgTitle = info.title ?: x.pkgTitle, titleId = info.titleId ?: x.titleId, iconReady = x.iconReady || info.hasIcon)
                    val tot = info.totalSize
                    if (tot != null && info.sizeTrusted && !x.expectedSource.startsWith("entered") && !x.expectedSource.contains("confirmed") && x.expectedSize != tot)
                        y = y.copy(expectedSize = tot, expectedSource = info.sizeNote)
                    y
                }
                if (info.totalSize != null && !info.sizeTrusted) d("PKG header says ${Fmt.bytes(info.totalSize)} but the fields do not agree; NOT applied")
                if (info.complete || st.attempts >= 12) st.done = true else st.nextAt = now + 20_000L * st.attempts
                syncNotifs()
            }
            else -> { st.nextAt = now + 30_000L * st.attempts; if (st.attempts >= 6) st.done = true }
        }
    }

    // ---------------- notifications (one stable id per download) ----------------
    fun syncNotifs() {
        val all = DownloadRepo.all.value
        for (x in all) {
            when {
                x.state.active && x.state != DlState.SUBMITTING -> Notifier.progress(x)
                x.state == DlState.STOPPED -> Notifier.cancel(x.notificationId)
                !x.terminalNotified && (x.state == DlState.COMPLETED || x.state == DlState.FAILED || x.state == DlState.NOT_STARTED) -> {
                    DownloadRepo.update(x.id) { it.copy(terminalNotified = true) }       // persist BEFORE posting: never twice
                    Notifier.result(x)
                }
            }
        }
    }
}
