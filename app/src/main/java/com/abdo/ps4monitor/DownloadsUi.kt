@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@Composable fun ConfirmDelete(ids: List<String>, onDone: () -> Unit, close: () -> Unit, defaultAlsoPs4: Boolean = false) {
    val n = ids.size; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var also by remember { mutableStateOf(defaultAlsoPs4) }
    var busy by remember { mutableStateOf(false) }
    val anyActive = ids.any { id -> DownloadRepo.get(id)?.state?.active == true }
    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(tr("Delete $n download(s)?", "حذف $n تحميل؟")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (also) tr("The download and its file(s) on the PS4 will be deleted over FTP. This cannot be undone.", "سيُحذف التحميل وملفاته على الـPS4 عبر FTP. لا يمكن التراجع.")
                 else tr("Removed from this list only. Files on the PS4 are kept, and a download still running on the PS4 keeps running.", "سيُحذف من هذه القائمة فقط. تبقى الملفات على الـPS4 وأي تحميل جارٍ سيستمر."))
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { also = !also }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(also, { also = it }, enabled = !busy); Text(tr("Also delete the file(s) from the PS4", "احذف الملف (الملفات) من الـPS4 أيضًا"), Modifier.weight(1f))
            }
            if (also && anyActive) Dim(tr("For a download still running, the PS4 may keep transferring data to the deleted file. This app cannot cancel ezRemote's transfer itself.",
                "لتحميل ما زال جاريًا قد يواصل الـPS4 نقل البيانات إلى الملف المحذوف. التطبيق لا يستطيع إلغاء نقل ezRemote نفسه."))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val msgs = ids.map { id -> if (also) DownloadMonitor.deleteFromPs4(id, true) else { DownloadMonitor.remove(id); "" } }.filter { it.isNotBlank() }
                if (msgs.isNotEmpty()) Toast.makeText(ctx, msgs.joinToString("\n").take(400), Toast.LENGTH_LONG).show()
                onDone(); close()
            }
        }) { Lbl(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Lbl(tr("Cancel", "إلغاء")) } })
}
private fun stopToast(ctx: android.content.Context) = Toast.makeText(ctx, tr("Monitoring stopped. The PS4 may still be downloading.", "أُوقفت المراقبة. قد يستمر الـPS4 في التحميل."), Toast.LENGTH_LONG).show()

@Composable fun DownloadsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val all by DownloadRepo.all.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) }
    val active = all.filter { it.state.active }
    val done = all.filter { it.state == DlState.COMPLETED }
    val failed = all.filter { it.state == DlState.FAILED || it.state == DlState.NOT_STARTED || it.state == DlState.STOPPED }
    val shown = when (tab) { 0 -> active; 1 -> done; else -> failed }.sortedByDescending { it.createdAt }
    val selIds = sel.filter { id -> shown.any { it.id == id } }       // forget anything that vanished or changed tab
    val selecting = selIds.isNotEmpty()
    BackHandler(enabled = selecting) { sel = emptySet() }
    // No "Paused" tab: ezRemote exposes no confirmed pause API, so pausing would be fake.
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selecting) {
                IconButton(onClick = { sel = emptySet() }) { Ico(R.drawable.ic_close) }
                Text("${selIds.size} " + tr("selected", "محدد"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { sel = shown.map { it.id }.toSet() }) { Ico(R.drawable.ic_select_all) }
                if (selIds.any { id -> all.firstOrNull { it.id == id }?.state?.active == true })
                    IconButton(onClick = { selIds.forEach { id -> if (all.firstOrNull { it.id == id }?.state?.active == true) DownloadMonitor.stop(id) }; sel = emptySet(); stopToast(ctx) }) { Ico(R.drawable.ic_stop) }
                IconButton(onClick = { confirm = selIds to false }) { Ico(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error) }
            } else {
                Text(tr("Downloads", "التحميلات"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                if (shown.isNotEmpty()) IconButton(onClick = { sel = shown.map { it.id }.toSet() }) { Ico(R.drawable.ic_select_all) }
                FilledTonalButton(onClick = { Inbox.url.value = "" }, contentPadding = PaddingValues(horizontal = 14.dp)) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Link", "رابط")) }
            }
        }
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, divider = {}) {
            listOf(tr("Active", "نشطة") to active.size, tr("Completed", "مكتملة") to done.size, tr("Failed", "فاشلة") to failed.size).forEachIndexed { i, (t, n) ->
                Tab(tab == i, { tab = i; sel = emptySet() }, text = { Lbl("$t $n") })
            }
        }
        if (shown.isEmpty()) EmptyState(R.drawable.ic_download, tr("Nothing here yet.", "لا يوجد شيء هنا بعد."))
        LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.id }) { d ->
                DownloadCard(d,
                    onOpen = { if (selecting) sel = if (d.id in sel) sel - d.id else sel + d.id else nav.navigate("downloads/${d.id}") },
                    onLong = { sel = sel + d.id }, selected = if (selecting) d.id in selIds else null,
                    onStop = { DownloadMonitor.stop(d.id); stopToast(ctx) }, onDelete = { confirm = listOf(d.id) to false })
            }
        }
    }
    confirm?.let { (ids, also) -> ConfirmDelete(ids, onDone = { sel = emptySet() }, close = { confirm = null }, defaultAlsoPs4 = also) }
}

@Composable fun DownloadDetail(id: String, nav: NavController) {
    val all by DownloadRepo.all.collectAsState()
    val untracked by DownloadMonitor.untracked.collectAsState()
    val d = all.firstOrNull { it.id == id }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Pair<List<String>, Boolean>?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackHeader(tr("Download", "التحميل"), nav)
        if (d == null) { Text(tr("This download was removed.", "تم حذف هذا التحميل.")); return@Column }
        val ps4 = Ps4Repo.get(d.ps4Id)
        val (bg, fg) = stateColors(d.state)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val art by produceState<Bitmap?>(null, d.id, d.iconReady) { value = if (d.iconReady) withContext(Dispatchers.IO) { PkgStore.bitmap("d:${d.id}", "icon0.png", 256) } else null }
            val pic = art
            if (pic != null) Image(pic.asImageBitmap(), null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
            else Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) { Ico(stateIcon(d.state), 28.dp, fg) }
            Column(Modifier.weight(1f)) {
                Text(d.pkgTitle ?: d.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Dim("${listOfNotNull(d.titleId).joinToString()}  ${ps4?.name ?: tr("Removed PS4", "جهاز محذوف")}  •  " + tr("attempt", "المحاولة") + " ${d.attempt}", maxLines = 1)
            }
        }
        Panel {
            Text(d.state.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            (d.errorMessage ?: d.note).takeIf { it.isNotBlank() }?.let { Text(Tx.t(it)) }
        }
        Panel {
            val exp = d.expectedSize?.takeIf { it > 0 }
            Text(tr("Size on PS4: ", "الحجم على الـPS4: ") + Fmt.bytes(d.currentSize))
            if (exp != null) {
                Text(tr("Total: ", "الإجمالي: ") + "${Fmt.bytes(exp)}  (${Tx.t(d.expectedSource.ifBlank { "known" })})")
                d.frac?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) }
                Text(tr("Progress: ", "التقدم: ") + "${d.pct}%")
            } else {
                Text(tr("Total: unknown — no percentage or ETA is shown", "الإجمالي: غير معروف — لا تُعرض نسبة ولا وقت متبقٍّ"))
                if (d.state.active) {
                    var st by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(st, { st = it }, label = { Text(tr("Set size, e.g. 47.5 GB", "حدد الحجم مثل 47.5 GB")) }, singleLine = true, modifier = Modifier.weight(1f))
                        Button(onClick = { val b = Fmt.parseSize(st); if (b > 0) DownloadMonitor.setExpected(d.id, b) }) { Lbl(tr("Set", "تعيين")) }
                    }
                }
            }
            Text("ETA: " + if (d.etaSec >= 0) Fmt.dur(d.etaSec) else "--")
        }
        if (d.state.active || d.peakSpeed > 0) Panel {
            Text(tr("Speed (from file growth): ", "السرعة (من نمو الملف): ") + "${Fmt.mbs(d.speed)}  •  ${Fmt.mbit(d.speed)}")
            Text(tr("Average (30 s): ", "المتوسط (30 ث): ") + "${Fmt.mbs(d.avgSpeed)}   " + tr("Peak: ", "الأعلى: ") + Fmt.mbs(d.peakSpeed))
            if (d.speeds.size > 1) Chart(d.speeds.map { it / 1048576f }, Modifier.fillMaxWidth().height(120.dp))
        }
        if (d.tempPath == null && d.state.active) {
            val dir = DownloadMonitor.norm(d.dest)
            val cands = untracked[d.ps4Id].orEmpty().filter { it.first == dir }
            if (cands.isNotEmpty()) Panel {
                Text(tr("Match the PS4 file yourself", "اختر ملف الـPS4 يدويًا"), fontWeight = FontWeight.SemiBold)
                Dim(tr("These .tmp files exist but could not be matched with certainty. Only choose one if you know it is this download.",
                       "هذه ملفات .tmp موجودة لكن لم يمكن ربطها بيقين. اختر واحدًا فقط إن كنت متأكدًا أنه هذا التحميل."))
                cands.forEach { (_, e) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Dim(Fmt.bytes(e.size)) }
                    TextButton(onClick = { DownloadMonitor.assignFile(d.id, e.name) }) { Lbl(tr("This one", "هذا")) }
                } }
            }
        }
        Panel {
            Dim(tr("Destination: ", "الوجهة: ") + d.dest)
            d.tempPath?.let { Dim(tr("Temporary file: ", "الملف المؤقت: ") + it) }
            d.finalPath?.let { Dim(tr("Final file: ", "الملف النهائي: ") + it) }
            if (d.sourceUrl.isNotBlank()) Dim(tr("Link: ", "الرابط: ") + d.sourceUrl.take(120))
            Dim(tr("Sent: ", "أُرسل: ") + Fmt.dt(d.submittedAt) + "   " + tr("Started: ", "بدأ: ") + Fmt.dt(d.startedAt) + "   " + tr("Finished: ", "انتهى: ") + Fmt.dt(d.completedAt))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.state.active) FilledTonalButton(onClick = { DownloadMonitor.stop(d.id); stopToast(ctx) }) { Ico(R.drawable.ic_stop, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Stop", "إيقاف")) }
            if (!d.superseded && d.sourceUrl.isNotBlank() && d.state in setOf(DlState.NOT_STARTED, DlState.FAILED, DlState.STOPPED))
                Button(onClick = { scope.launch { Toast.makeText(ctx, resultText(DownloadMonitor.retry(d.id)), Toast.LENGTH_LONG).show() } }) { Ico(R.drawable.ic_refresh, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Retry", "إعادة المحاولة")) }
            if (!d.superseded && d.state in setOf(DlState.NOT_STARTED, DlState.FAILED, DlState.STOPPED))
                OutlinedButton(onClick = { DownloadMonitor.resume(d.id) }) { Lbl(tr("Resume monitoring", "استئناف المراقبة")) }
            OutlinedButton(onClick = { confirm = listOf(d.id) to false }) { Ico(R.drawable.ic_delete, 18.dp, MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)); Lbl(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error) }
            if (d.tempPath != null || d.finalPath != null) Button(onClick = { confirm = listOf(d.id) to true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Ico(R.drawable.ic_delete, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Delete from PS4", "حذف من الـPS4")) }
            if (ps4 != null && PkgInspector.ftpOn(ps4) && (d.tempPath ?: d.finalPath) != null && (d.iconReady || (d.finalPath ?: d.tempPath).orEmpty().lowercase().contains("pkg") || d.displayName.lowercase().endsWith(".pkg")))
                FilledTonalButton(onClick = { nav.navigate(pkgRoute(ps4.id, d.finalPath ?: d.tempPath!!)) }) { Ico(R.drawable.ic_package, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Inside the PKG", "ما بداخل الـPKG")) }
        }
        if (d.state.active) Dim(tr("“Stop” only stops this app from watching; it does not cancel the download on the PS4 (ezRemote has no confirmed cancel API). Retry sends a NEW request and is never automatic.",
            "«إيقاف» يوقف مراقبة التطبيق فقط ولا يلغي التحميل على الـPS4 (لا يوجد API مؤكد للإلغاء في ezRemote). إعادة المحاولة ترسل طلبًا جديدًا ولا تتم تلقائيًا."))
    }
    confirm?.let { (ids, also) -> ConfirmDelete(ids, onDone = { nav.popBackStack() }, close = { confirm = null }, defaultAlsoPs4 = also) }
}
