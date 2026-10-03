@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

fun joinPath(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

private enum class FKind { DIR, PKG, TMP, IMAGE, ARCHIVE, OTHER }
private fun kindOf(e: FsEntry): FKind {
    if (e.isDir) return FKind.DIR
    val n = e.name.lowercase(); val ext = n.substringAfterLast('.', "")
    return when { n.endsWith(".pkg") -> FKind.PKG; n.endsWith(".tmp") -> FKind.TMP
        ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> FKind.IMAGE; ext in setOf("zip", "rar", "7z", "tar", "gz") -> FKind.ARCHIVE; else -> FKind.OTHER }
}
private fun kindIcon(k: FKind) = when (k) { FKind.DIR -> R.drawable.ic_folder; FKind.PKG, FKind.ARCHIVE -> R.drawable.ic_package; FKind.TMP -> R.drawable.ic_schedule; FKind.IMAGE -> R.drawable.ic_image; FKind.OTHER -> R.drawable.ic_file }

private sealed class FilesState { object Loading : FilesState(); class Ok(val items: List<FsEntry>) : FilesState(); class Err(val msg: String) : FilesState() }

/** Confirmation + execution of a real delete on the PS4 (FTP). Shared by Files and Home. */
@Composable fun ConfirmPs4Delete(ps4: Ps4, items: List<Pair<String, FsEntry>>, onDone: () -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val total = items.filter { !it.second.isDir }.sumOf { it.second.size }
    val outside = items.any { Remote.outside(ps4, joinPath(it.first, it.second.name)) }
    val hasDir = items.any { it.second.isDir }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(tr("Delete from the PS4?", "حذف من الـPS4؟")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("${items.size} item(s) will be deleted from ${ps4.name} over FTP", "سيُحذف ${items.size} عنصر من ${ps4.name} عبر FTP") + (if (total > 0) " (${Fmt.bytes(total)})" else "") + tr(". This cannot be undone.", ". لا يمكن التراجع."))
            items.take(6).forEach { Dim(joinPath(it.first, it.second.name), maxLines = 2) }
            if (items.size > 6) Dim("+ ${items.size - 6}")
            if (hasDir) Dim(tr("Folders are removed only if they are empty.", "المجلدات تُحذف فقط إذا كانت فارغة."))
            if (outside) Text(tr("Some items are outside this PS4's download folder (${ps4.dest}). They may be system or game files.", "بعض العناصر خارج مجلد التحميل لهذا الـPS4 (${ps4.dest}). قد تكون ملفات نظام أو ألعاب."),
                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val msg = try { Remote.summary(withContext(Dispatchers.IO) { Remote.delete(ps4, items.map { Remote.Item(joinPath(it.first, it.second.name), it.second.isDir) }) }) }
                          catch (e: Exception) { Tx.t(PkgInspector.friendly(e)) }
                Toast.makeText(ctx, msg.take(400), Toast.LENGTH_LONG).show(); onDone(); close()
            }
        }) { Lbl(tr("Delete", "حذف"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Lbl(tr("Cancel", "إلغاء")) } })
}

@Composable private fun FileRow(ps4: Ps4, dir: String, e: FsEntry, selected: Boolean?, tick: Int, onClick: () -> Unit, onLong: () -> Unit, onDelete: () -> Unit) {
    val k = kindOf(e); val shape = MaterialTheme.shapes.medium
    val thumb by produceState<Bitmap?>(null, e.name, e.size, tick) {
        value = if (k == FKind.PKG || k == FKind.TMP) withContext(Dispatchers.IO) { PkgStore.bitmap(PkgStore.key(ps4.id, joinPath(dir, e.name), e.size), "icon0.png", 120) } else null
    }
    Card(Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick, onLongClick = onLong), shape = shape,
        colors = CardDefaults.cardColors(containerColor = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                val b = thumb
                if (selected == true) Ico(R.drawable.ic_check, 24.dp, MaterialTheme.colorScheme.primary)
                else if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Ico(kindIcon(k), 24.dp, if (k == FKind.DIR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(e.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Dim(if (e.isDir) tr("Folder", "مجلد") else Fmt.bytes(e.size) + (if (e.mtime > 0) "  •  " + Fmt.dt(e.mtime) else ""), maxLines = 1)
            }
            if (selected == null) {
                if (k == FKind.DIR) Ico(R.drawable.ic_arrow_forward, 20.dp, MaterialTheme.colorScheme.outline, Modifier.padding(end = 12.dp))
                else IconButton(onClick = onDelete) { Ico(R.drawable.ic_delete, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable fun FilesScreen(nav: NavController) {
    val ps4s by Ps4Repo.list.collectAsState(); val activeId by Ps4Repo.activeId.collectAsState()
    val ps4 = ps4s.firstOrNull { it.id == activeId }
    if (ps4 == null) {
        Column(Modifier.padding(16.dp)) { BackHeader(tr("PS4 files", "ملفات PS4"), nav); EmptyState(R.drawable.ic_console, tr("Add a PS4 first.", "أضف جهاز PS4 أولًا.")) }
        return
    }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val start = DownloadMonitor.norm(ps4.dest)
    var path by rememberSaveable(ps4.id) { mutableStateOf(start) }
    var reload by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<List<Pair<String, FsEntry>>?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var scan by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val state by produceState<FilesState>(FilesState.Loading, ps4.id, path, reload) {
        value = FilesState.Loading
        value = withContext(Dispatchers.IO) {
            try { FilesState.Ok(PkgInspector.browse(ps4, path).sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })) }
            catch (e: Exception) { FilesState.Err(Tx.t(PkgInspector.friendly(e))) }
        }
    }
    LaunchedEffect(path) { sel = emptySet() }
    val listing = (state as? FilesState.Ok)?.items.orEmpty()
    val selected = listing.filter { it.name in sel }
    BackHandler(enabled = selected.isNotEmpty()) { sel = emptySet() }
    BackHandler(enabled = selected.isEmpty() && path != start && path != "/") { path = path.substringBeforeLast('/', "/").ifEmpty { "/" } }

    fun openImage(e: FsEntry) {
        if (!PkgInspector.ftpOn(ps4)) { Toast.makeText(ctx, tr("Preview needs FTP.", "المعاينة تحتاج FTP."), Toast.LENGTH_SHORT).show(); return }
        val full = joinPath(path, e.name)
        scope.launch {
            val b = withContext(Dispatchers.IO) { runCatching {
                val bytes = Ftp.readRange(Conn(ps4.host, ps4.ftpPort, ps4.ftpUser, ps4.ftpPass), Store.settings(), full, 0, minOf(e.size, 8L shl 20).toInt())
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
            if (b != null) preview = b else Toast.makeText(ctx, tr("Cannot preview this file.", "تعذّرت معاينة هذا الملف."), Toast.LENGTH_SHORT).show()
        }
    }
    fun startScan() {
        if (!PkgInspector.ftpOn(ps4)) { Toast.makeText(ctx, tr("Reading PKG files needs FTP.", "قراءة ملفات PKG تحتاج FTP."), Toast.LENGTH_SHORT).show(); return }
        val dir = path; val todo = listing.filter { kindOf(it).let { k -> k == FKind.PKG || k == FKind.TMP } }
        scanJob?.cancel()
        scanJob = scope.launch {
            todo.forEachIndexed { i, e ->
                scan = "${i + 1}/${todo.size}"
                val full = joinPath(dir, e.name); val key = PkgStore.key(ps4.id, full, e.size)
                if (PkgStore.load(key)?.hasIcon != true) { PkgInspector.mutex.withLock { withContext(Dispatchers.IO) { PkgInspector.inspect(ps4, full, e.size, key, false) } }; tick++ }
            }
            scan = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected.isNotEmpty()) {
                IconButton(onClick = { sel = emptySet() }) { Ico(R.drawable.ic_close) }
                Text("${selected.size} " + tr("selected", "محدد"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { sel = listing.map { it.name }.toSet() }) { Ico(R.drawable.ic_select_all) }
                IconButton(onClick = { confirm = selected.map { path to it } }) { Ico(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error) }
            } else {
                IconButton(onClick = { nav.popBackStack() }) { Ico(R.drawable.ic_arrow_back) }
                Column(Modifier.weight(1f)) {
                    Text(tr("PS4 files", "ملفات PS4"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Dim(ps4.name, maxLines = 1)
                }
                if (scan != null) { Lbl(scan.orEmpty(), style = MaterialTheme.typography.labelMedium); IconButton(onClick = { scanJob?.cancel(); scan = null }) { Ico(R.drawable.ic_close, 20.dp) } }
                else if (listing.any { kindOf(it).let { k -> k == FKind.PKG || k == FKind.TMP } }) IconButton(onClick = { startScan() }) { Ico(R.drawable.ic_image) }
                IconButton(onClick = { reload++ }) { Ico(R.drawable.ic_refresh) }
            }
        }
        val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (path != "/") IconButton(onClick = { path = path.substringBeforeLast('/', "/").ifEmpty { "/" } }) { Ico(R.drawable.ic_arrow_up, 20.dp) }
            TextButton(onClick = { path = "/" }, contentPadding = PaddingValues(horizontal = 8.dp)) { Lbl("/") }
            segs.forEachIndexed { i, sname ->
                Ico(R.drawable.ic_arrow_forward, 14.dp, MaterialTheme.colorScheme.outline)
                TextButton(onClick = { path = "/" + segs.take(i + 1).joinToString("/") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Lbl(sname, weight = if (i == segs.lastIndex) FontWeight.Bold else null) }
            }
        }
        when (val st = state) {
            is FilesState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            is FilesState.Err -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EmptyState(R.drawable.ic_error, st.msg); Button(onClick = { reload++ }) { Lbl(tr("Retry", "إعادة المحاولة")) } }
            is FilesState.Ok -> {
                val files = st.items.filter { !it.isDir }
                Dim(tr("${st.items.size - files.size} folder(s)  •  ${files.size} file(s)  •  ${Fmt.bytes(files.sumOf { it.size })}", "${st.items.size - files.size} مجلد  •  ${files.size} ملف  •  ${Fmt.bytes(files.sumOf { it.size })}"), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (st.items.isEmpty()) EmptyState(R.drawable.ic_folder, tr("This folder is empty.", "هذا المجلد فارغ."))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(st.items, key = { it.name }) { e ->
                        val selecting = selected.isNotEmpty(); val k = kindOf(e)
                        FileRow(ps4, path, e, if (selecting) e.name in sel else null, tick,
                            onClick = {
                                if (selecting) sel = if (e.name in sel) sel - e.name else sel + e.name
                                else when (k) {
                                    FKind.DIR -> path = joinPath(path, e.name)
                                    FKind.PKG, FKind.TMP -> nav.navigate(pkgRoute(ps4.id, joinPath(path, e.name)))
                                    FKind.IMAGE -> openImage(e)
                                    else -> Toast.makeText(ctx, "${e.name}  •  ${Fmt.bytes(e.size)}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onLong = { sel = sel + e.name }, onDelete = { confirm = listOf(path to e) })
                    }
                }
            }
        }
    }
    confirm?.let { ConfirmPs4Delete(ps4, it, onDone = { sel = emptySet(); reload++ }, close = { confirm = null }) }
    preview?.let { b -> Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().clickable { preview = null }, contentScale = ContentScale.Fit) } }
}
