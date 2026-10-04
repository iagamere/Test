@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

fun joinPath(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

/** Copy / cut clipboard shared by all folders of the Files screen (survives navigation between folders). */
object FsClip {
    var ps4Id by mutableStateOf<String?>(null)
    var items by mutableStateOf<List<Pair<String, FsEntry>>>(emptyList())
    var move by mutableStateOf(false)
    fun set(ps4: String, list: List<Pair<String, FsEntry>>, mv: Boolean) { ps4Id = ps4; items = list; move = mv }
    fun clear() { items = emptyList(); ps4Id = null }
}

private enum class FKind { DIR, PKG, TMP, IMAGE, ARCHIVE, TEXT, OTHER }
private val TEXT_EXT = setOf("txt", "json", "xml", "ini", "cfg", "conf", "log", "md", "yml", "yaml", "lst", "csv", "html", "js", "lua", "sfx")
private val ARCHIVE_EXT = setOf("zip", "rar", "7z")
private fun kindOf(e: FsEntry): FKind {
    if (e.isDir) return FKind.DIR
    val n = e.name.lowercase(); val ext = n.substringAfterLast('.', "")
    return when { n.endsWith(".pkg") -> FKind.PKG; n.endsWith(".tmp") -> FKind.TMP
        ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") -> FKind.IMAGE; ext in ARCHIVE_EXT -> FKind.ARCHIVE; ext in TEXT_EXT -> FKind.TEXT; else -> FKind.OTHER }
}
private fun kindIcon(k: FKind) = when (k) { FKind.DIR -> R.drawable.ic_folder; FKind.PKG, FKind.ARCHIVE -> R.drawable.ic_package; FKind.TMP -> R.drawable.ic_schedule; FKind.IMAGE -> R.drawable.ic_image; FKind.TEXT, FKind.OTHER -> R.drawable.ic_file }

private sealed class FilesState { object Loading : FilesState(); class Ok(val items: List<FsEntry>) : FilesState(); class Err(val msg: String) : FilesState() }

/** Hands a PS4 file to Android's DownloadManager (background, resumable, notification, lands in Downloads). */
private fun saveToPhone(ctx: Context, p: Ps4, path: String) {
    val name = path.substringAfterLast('/')
    try {
        val req = DownloadManager.Request(Uri.parse("http://${p.host}:${p.httpPort}/__local__/downloadFile?path=" + Uri.encode(path)))
            .setTitle(name).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        Toast.makeText(ctx, tr("Saving to the phone's Downloads folder…", "جارٍ الحفظ في مجلد التنزيلات بالهاتف…"), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) { Toast.makeText(ctx, tr("Could not start the download: ", "تعذّر بدء التنزيل: ") + (e.message ?: ""), Toast.LENGTH_LONG).show() }
}

// ---------------------------------------------------------------- dialogs
@Composable fun ConfirmInstall(ps4: Ps4, paths: List<String>, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(tr("Install on the PS4?", "تثبيت على الـPS4؟")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("ezRemote will start installing ${paths.size} package(s) on ${ps4.name}.", "سيبدأ ezRemote تثبيت ${paths.size} حزمة على ${ps4.name}."))
            paths.take(4).forEach { Dim(it, maxLines = 2) }
            Dim(tr("This app only sends the request; the install progress is shown by the PS4 itself.", "التطبيق يرسل الطلب فقط؛ الـPS4 نفسه يعرض تقدم التثبيت."))
        } },
        confirmButton = { TextButton(onClick = { Ops.launch(tr("Installing…", "جارٍ التثبيت…")) { Ops.install(ps4, paths) }; close() }) { Lbl(tr("Install", "تثبيت")) } },
        dismissButton = { TextButton(onClick = close) { Lbl(tr("Cancel", "إلغاء")) } })
}

@Composable private fun NameDialog(title: String, label: String, initial: String, confirm: String, onOk: (String) -> Unit, close: () -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = v.isNotBlank() && '/' !in v, onClick = { onOk(v.trim()); close() }) { Lbl(confirm) } },
        dismissButton = { TextButton(onClick = close) { Lbl(tr("Cancel", "إلغاء")) } })
}

@Composable private fun SimpleConfirm(title: String, text: String, confirm: String, onOk: () -> Unit, close: () -> Unit) =
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onOk(); close() }) { Lbl(confirm) } }, dismissButton = { TextButton(onClick = close) { Lbl(tr("Cancel", "إلغاء")) } })

/** Confirmation + execution of a real delete on the PS4 (ezRemote remove / FTP). Shared by Files and Home. */
@Composable fun ConfirmPs4Delete(ps4: Ps4, items: List<Pair<String, FsEntry>>, onDone: () -> Unit, close: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val total = items.filter { !it.second.isDir }.sumOf { it.second.size }
    val outside = items.any { Remote.outside(ps4, joinPath(it.first, it.second.name)) }
    val hasDir = items.any { it.second.isDir }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(tr("Delete from the PS4?", "حذف من الـPS4؟")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("${items.size} item(s) will be deleted from ${ps4.name}", "سيُحذف ${items.size} عنصر من ${ps4.name}") + (if (total > 0) " (${Fmt.bytes(total)})" else "") + tr(". This cannot be undone.", ". لا يمكن التراجع."))
            items.take(6).forEach { Dim(joinPath(it.first, it.second.name), maxLines = 2) }
            if (items.size > 6) Dim("+ ${items.size - 6}")
            if (hasDir) Text(tr("Folders are deleted TOGETHER WITH EVERYTHING INSIDE THEM.", "المجلدات تُحذف مع كل ما بداخلها."), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
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

// ---------------------------------------------------------------- list row
@Composable private fun FileRow(ps4: Ps4, dir: String, e: FsEntry, selected: Boolean?, tick: Int, onClick: () -> Unit, onLong: () -> Unit, onDelete: () -> Unit) {
    val k = kindOf(e); val shape = MaterialTheme.shapes.medium
    val th by produceState<Thumb?>(null, ps4.id, dir, e.name, e.size, tick) {
        value = if (k == FKind.PKG || k == FKind.TMP) PkgThumbs.get(ps4, joinPath(dir, e.name), e.size, 120) else null     // loads by itself, queued
    }
    Card(Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onClick, onLongClick = onLong), shape = shape,
        colors = CardDefaults.cardColors(containerColor = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                val b = th?.bmp
                if (selected == true) Ico(R.drawable.ic_check, 24.dp, MaterialTheme.colorScheme.primary)
                else if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Ico(kindIcon(k), 24.dp, if (k == FKind.DIR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(e.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                th?.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Dim(if (e.isDir) tr("Folder", "مجلد") else Fmt.bytes(e.size) + (if (e.mtime > 0) "  •  " + Fmt.dt(e.mtime) else ""), maxLines = 1)
            }
            if (selected == null) {
                if (k == FKind.DIR) Ico(R.drawable.ic_arrow_forward, 20.dp, MaterialTheme.colorScheme.outline, Modifier.padding(end = 12.dp))
                else IconButton(onClick = onDelete) { Ico(R.drawable.ic_delete, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---------------------------------------------------------------- screen
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
    val opTick by Ops.refresh.collectAsState()
    val opsBusy by Ops.active.collectAsState(); val opsLabel by Ops.label.collectAsState(); val opsPct by Ops.uploadPct.collectAsState()
    var sel by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var selMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<Pair<String, FsEntry>>?>(null) }
    var confirmInstall by remember { mutableStateOf<List<String>?>(null) }
    var confirmExtract by remember { mutableStateOf<String?>(null) }
    var confirmSave by remember { mutableStateOf<FsEntry?>(null) }
    var renaming by remember { mutableStateOf<FsEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var pasteConflicts by remember { mutableStateOf<Int?>(null) }
    var uploadPlan by remember { mutableStateOf<UploadPlan?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var scan by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val state by produceState<FilesState>(FilesState.Loading, ps4.id, path, reload, opTick) {
        value = FilesState.Loading
        value = withContext(Dispatchers.IO) {
            try { FilesState.Ok(PkgInspector.browse(ps4, path).sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })) }
            catch (e: Exception) { FilesState.Err(Tx.t(PkgInspector.friendly(e))) }
        }
    }
    LaunchedEffect(path) { sel = emptySet() }
    val listing = (state as? FilesState.Ok)?.items.orEmpty()
    val selected = listing.filter { it.name in sel }
    val clip = FsClip.items.takeIf { FsClip.ps4Id == ps4.id }.orEmpty()
    BackHandler(enabled = selected.isNotEmpty()) { sel = emptySet() }
    BackHandler(enabled = selected.isEmpty() && path != start && path != "/") { path = path.substringBeforeLast('/', "/").ifEmpty { "/" } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { val dir = path; scope.launch { try { uploadPlan = Ops.planUpload(ps4, uri, dir) } catch (e: Exception) { Toast.makeText(ctx, Tx.t(PkgInspector.friendly(e)), Toast.LENGTH_LONG).show() } } }
    }

    fun openImage(e: FsEntry) {
        if (!PkgInspector.canRead(ps4)) { Toast.makeText(ctx, tr("Preview needs a web or FTP connection.", "المعاينة تحتاج اتصال ويب أو FTP."), Toast.LENGTH_SHORT).show(); return }
        val full = joinPath(path, e.name)
        scope.launch {
            val b = withContext(Dispatchers.IO) { runCatching {
                val bytes = PkgInspector.readBytes(ps4, full, 0, minOf(e.size, 8L shl 20).toInt())
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() }
            if (b != null) preview = b else Toast.makeText(ctx, tr("Cannot preview this file.", "تعذّرت معاينة هذا الملف."), Toast.LENGTH_SHORT).show()
        }
    }
    fun startScan() {
        if (!PkgInspector.canRead(ps4)) return
        val dir = path; val todo = listing.filter { kindOf(it).let { k -> k == FKind.PKG || k == FKind.TMP } }
        scanJob?.cancel()
        scanJob = scope.launch { todo.forEachIndexed { i, e -> scan = "${i + 1}/${todo.size}"; PkgThumbs.get(ps4, joinPath(dir, e.name), e.size, 120); tick++ }; scan = null }
    }
    fun runPaste() {
        val items = FsClip.items; val mv = FsClip.move; val dest = path
        if (Ops.launch(tr(if (mv) "Moving ${items.size} item(s)" else "Copying ${items.size} item(s)", if (mv) "نقل ${items.size} عنصر" else "نسخ ${items.size} عنصر")) { Ops.copyMove(ps4, items, dest, mv) }) FsClip.clear()
    }
    fun openItem(e: FsEntry) {
        when (kindOf(e)) {
            FKind.DIR -> path = joinPath(path, e.name)
            FKind.PKG, FKind.TMP -> nav.navigate(pkgRoute(ps4.id, joinPath(path, e.name)))
            FKind.IMAGE -> openImage(e)
            FKind.TEXT -> if (e.size <= 256 * 1024) nav.navigate(textRoute(ps4.id, joinPath(path, e.name))) else Toast.makeText(ctx, tr("Too large to edit here (max 256 KB).", "كبير جدًا للتحرير هنا (الحد 256 KB)."), Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(ctx, "${e.name}  •  ${Fmt.bytes(e.size)}", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected.isNotEmpty()) {
                val single = selected.singleOrNull()
                IconButton(onClick = { sel = emptySet() }) { Ico(R.drawable.ic_close) }
                Text("${selected.size} " + tr("selected", "محدد"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { FsClip.set(ps4.id, selected.map { path to it }, false); sel = emptySet(); Toast.makeText(ctx, tr("Copied. Open a folder and tap Paste.", "تم النسخ. افتح مجلدًا واضغط لصق."), Toast.LENGTH_SHORT).show() }) { Ico(R.drawable.ic_copy) }
                IconButton(onClick = { FsClip.set(ps4.id, selected.map { path to it }, true); sel = emptySet(); Toast.makeText(ctx, tr("Cut. Open a folder and tap Paste.", "تم القص. افتح مجلدًا واضغط لصق."), Toast.LENGTH_SHORT).show() }) { Ico(R.drawable.ic_move) }
                IconButton(onClick = { confirmDelete = selected.map { path to it } }) { Ico(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error) }
                Box {
                    IconButton(onClick = { selMenu = true }) { Ico(R.drawable.ic_more) }
                    DropdownMenu(selMenu, { selMenu = false }) {
                        DropdownMenuItem(text = { Text(tr("Select all", "تحديد الكل")) }, leadingIcon = { Ico(R.drawable.ic_select_all, 20.dp) }, onClick = { selMenu = false; sel = listing.map { it.name }.toSet() })
                        if (single != null) {
                            DropdownMenuItem(text = { Text(tr("Rename", "إعادة تسمية")) }, leadingIcon = { Ico(R.drawable.ic_edit, 20.dp) }, onClick = { selMenu = false; renaming = single })
                            val k = kindOf(single)
                            if (k == FKind.PKG) DropdownMenuItem(text = { Text(tr("Install on PS4", "تثبيت على الـPS4")) }, leadingIcon = { Ico(R.drawable.ic_install, 20.dp) }, onClick = { selMenu = false; confirmInstall = listOf(joinPath(path, single.name)) })
                            if (k == FKind.ARCHIVE) DropdownMenuItem(text = { Text(tr("Extract here", "فك الضغط هنا")) }, leadingIcon = { Ico(R.drawable.ic_unarchive, 20.dp) }, onClick = { selMenu = false; confirmExtract = joinPath(path, single.name) })
                            if (k != FKind.DIR) DropdownMenuItem(text = { Text(tr("Save to phone", "حفظ في الهاتف")) }, leadingIcon = { Ico(R.drawable.ic_download, 20.dp) }, onClick = { selMenu = false; confirmSave = single })
                        } else if (selected.all { kindOf(it) == FKind.PKG }) {
                            DropdownMenuItem(text = { Text(tr("Install ${selected.size} packages", "تثبيت ${selected.size} حزمة")) }, leadingIcon = { Ico(R.drawable.ic_install, 20.dp) }, onClick = { selMenu = false; confirmInstall = selected.map { joinPath(path, it.name) } })
                        }
                    }
                }
            } else {
                IconButton(onClick = { nav.popBackStack() }) { Ico(R.drawable.ic_arrow_back) }
                Column(Modifier.weight(1f)) {
                    Text(tr("PS4 files", "ملفات PS4"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Dim(ps4.name, maxLines = 1)
                }
                if (scan != null) { Lbl(scan.orEmpty(), style = MaterialTheme.typography.labelMedium); IconButton(onClick = { scanJob?.cancel(); scan = null }) { Ico(R.drawable.ic_close, 20.dp) } }
                IconButton(onClick = { reload++ }) { Ico(R.drawable.ic_refresh) }
                Box {
                    IconButton(onClick = { menu = true }) { Ico(R.drawable.ic_more) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(tr("New folder", "مجلد جديد")) }, leadingIcon = { Ico(R.drawable.ic_folder_add, 20.dp) }, onClick = { menu = false; newFolder = true })
                        DropdownMenuItem(text = { Text(tr("Upload a file here", "رفع ملف إلى هنا")) }, leadingIcon = { Ico(R.drawable.ic_upload, 20.dp) }, onClick = { menu = false; picker.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text(tr("Load all artwork", "تحميل كل الصور")) }, leadingIcon = { Ico(R.drawable.ic_image, 20.dp) }, onClick = { menu = false; startScan() })
                        DropdownMenuItem(text = { Text(tr("Select all", "تحديد الكل")) }, leadingIcon = { Ico(R.drawable.ic_select_all, 20.dp) }, onClick = { menu = false; sel = listing.map { it.name }.toSet() })
                    }
                }
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
        if (opsBusy > 0) Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(opsLabel + if (opsPct >= 0) "  $opsPct%" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    if (opsPct >= 0) LinearProgressIndicator(progress = { opsPct / 100f }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                }
                TextButton(onClick = { Ops.cancel() }) { Lbl(tr("Cancel", "إلغاء")) }
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
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(st.items, key = { it.name }) { e ->
                        val selecting = selected.isNotEmpty()
                        FileRow(ps4, path, e, if (selecting) e.name in sel else null, tick,
                            onClick = { if (selecting) sel = if (e.name in sel) sel - e.name else sel + e.name else openItem(e) },
                            onLong = { sel = sel + e.name }, onDelete = { confirmDelete = listOf(path to e) })
                    }
                }
            }
        }
        if (clip.isNotEmpty()) Card(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ico(if (FsClip.move) R.drawable.ic_move else R.drawable.ic_copy, 22.dp)
                Text(tr("${clip.size} item(s) to " + (if (FsClip.move) "move" else "copy"), "${clip.size} عنصر لـ" + (if (FsClip.move) "النقل" else "النسخ")), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button(onClick = {
                    val have = listing.map { it.name }.toSet(); val n = clip.count { it.second.name in have }
                    if (n > 0) pasteConflicts = n else runPaste()
                }, contentPadding = PaddingValues(horizontal = 14.dp)) { Ico(R.drawable.ic_paste, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Paste here", "لصق هنا")) }
                IconButton(onClick = { FsClip.clear() }) { Ico(R.drawable.ic_close, 20.dp) }
            }
        }
    }

    confirmDelete?.let { ConfirmPs4Delete(ps4, it, onDone = { sel = emptySet(); reload++ }, close = { confirmDelete = null }) }
    confirmInstall?.let { ConfirmInstall(ps4, it, close = { confirmInstall = null; sel = emptySet() }) }
    confirmExtract?.let { a -> SimpleConfirm(tr("Extract on the PS4?", "فك الضغط على الـPS4؟"),
        tr("${a.substringAfterLast('/')} will be extracted into a new folder next to it. This can take a long time and uses the PS4's storage.", "سيُفك ${a.substringAfterLast('/')} في مجلد جديد بجانبه. قد يستغرق وقتًا طويلًا ويستهلك مساحة الـPS4."),
        tr("Extract", "فك الضغط"), { Ops.launch(tr("Extracting ${a.substringAfterLast('/')}", "فك ضغط ${a.substringAfterLast('/')}")) { Ops.extract(ps4, a) }; sel = emptySet() }, { confirmExtract = null }) }
    confirmSave?.let { e -> SimpleConfirm(tr("Save to the phone?", "حفظ في الهاتف؟"), "${e.name}  •  ${Fmt.bytes(e.size)}\n" + tr("It will be downloaded to the phone's Downloads folder.", "سيُنزَّل إلى مجلد التنزيلات في الهاتف."),
        tr("Save", "حفظ"), { saveToPhone(ctx, ps4, joinPath(path, e.name)); sel = emptySet() }, { confirmSave = null }) }
    renaming?.let { e -> NameDialog(tr("Rename", "إعادة تسمية"), tr("New name", "الاسم الجديد"), e.name, tr("Rename", "إعادة تسمية"),
        { n -> val from = joinPath(path, e.name); Ops.launch(tr("Renaming ${e.name}", "إعادة تسمية ${e.name}")) { Ops.renameItem(ps4, from, n) }; sel = emptySet() }, { renaming = null }) }
    if (newFolder) NameDialog(tr("New folder", "مجلد جديد"), tr("Folder name", "اسم المجلد"), "", tr("Create", "إنشاء"),
        { n -> val full = joinPath(path, n); Ops.launch(tr("Creating folder $n", "إنشاء المجلد $n")) { Ops.mkdir(ps4, full) } }, { newFolder = false })
    pasteConflicts?.let { n -> SimpleConfirm(tr("Replace existing items?", "استبدال العناصر الموجودة؟"),
        tr("$n item(s) with the same name already exist here and will be overwritten.", "$n عنصر بنفس الاسم موجود هنا وسيُستبدل."), tr("Overwrite", "استبدال"), { runPaste() }, { pasteConflicts = null }) }
    uploadPlan?.let { pl ->
        val partial = pl.existing in 1 until pl.total
        AlertDialog(onDismissRequest = { uploadPlan = null }, title = { Text(tr("Upload to the PS4", "رفع إلى الـPS4")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${pl.name}  •  ${Fmt.bytes(pl.total)}", fontWeight = FontWeight.SemiBold)
                Dim(tr("Destination: ", "الوجهة: ") + pl.destDir)
                if (partial) Text(tr("A partial file already exists (${Fmt.bytes(pl.existing)} of ${Fmt.bytes(pl.total)}). Resume it only if it is the same file.", "يوجد ملف جزئي (${Fmt.bytes(pl.existing)} من ${Fmt.bytes(pl.total)}). استأنفه فقط إذا كان نفس الملف."))
                else if (pl.existing > 0) Text(tr("A file with this name already exists and will be overwritten.", "يوجد ملف بهذا الاسم وسيُستبدل."), color = MaterialTheme.colorScheme.error)
                Dim(tr("The upload continues in the background while the app stays open.", "يستمر الرفع في الخلفية ما دام التطبيق يعمل."))
            } },
            confirmButton = { Row {
                if (partial) TextButton(onClick = { Ops.launch(tr("Uploading ${pl.name}", "رفع ${pl.name}")) { Ops.upload(ps4, pl, true) }; uploadPlan = null }) { Lbl(tr("Resume", "استئناف")) }
                TextButton(onClick = { Ops.launch(tr("Uploading ${pl.name}", "رفع ${pl.name}")) { Ops.upload(ps4, pl, false) }; uploadPlan = null }) { Lbl(if (partial || pl.existing > 0) tr("Overwrite", "استبدال") else tr("Upload", "رفع")) }
            } },
            dismissButton = { TextButton(onClick = { uploadPlan = null }) { Lbl(tr("Cancel", "إلغاء")) } })
    }
    preview?.let { b -> Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().clickable { preview = null }, contentScale = ContentScale.Fit) } }
}
