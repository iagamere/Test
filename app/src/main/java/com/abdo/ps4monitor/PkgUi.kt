@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private sealed class PkgState {
    object Loading : PkgState()
    class Err(val msg: String, val notPkg: Boolean) : PkgState()
    class Ok(val info: PkgInfo, val icon: Bitmap?, val pic0: Bitmap?, val pic1: Bitmap?, val size: Long) : PkgState()
}

private suspend fun loadPkg(p: Ps4?, path: String, force: Boolean): PkgState = withContext(Dispatchers.IO) {
    if (p == null) return@withContext PkgState.Err("PS4 profile not found", false)
    val sz: Long? = try { PkgInspector.sizeOf(p, path) } catch (e: Exception) { return@withContext PkgState.Err(Tx.t(PkgInspector.friendly(e)), false) }
    if (sz == null) return@withContext PkgState.Err(tr("File not found on the PS4.", "الملف غير موجود على الـPS4."), false)
    val key = PkgStore.key(p.id, path, sz)
    var info = if (force) null else PkgStore.load(key)?.takeIf { it.full && it.complete && it.fileSizeAtRead == sz }
    var err: String? = null; var notPkg = false
    if (info == null) {
        val r = PkgInspector.mutex.withLock { PkgInspector.inspect(p, path, sz, key, true) }
        info = r.info; err = r.error; notPkg = r.notPkg
    }
    if (info == null) return@withContext PkgState.Err(Tx.t(err ?: "Unknown error"), notPkg)
    PkgState.Ok(info, PkgStore.bitmap(key, "icon0.png", 512), PkgStore.bitmap(key, "pic0.png", 1280), PkgStore.bitmap(key, "pic1.png", 1280), sz)
}

@Composable private fun KV(k: String, v: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(k, Modifier.weight(0.38f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(v, Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}

private fun report(i: PkgInfo, path: String, size: Long): String = buildString {
    appendLine("PKG report"); appendLine("path: $path"); appendLine("file size now: $size")
    appendLine("content_id: ${i.contentId}"); appendLine("title: ${i.title}  title_id: ${i.titleId}  app_ver: ${i.appVer}  category: ${i.category}  sys: ${i.systemVer}")
    appendLine("pkg_size@0x448: ${i.rawPkgSize}  pfs_image: off=${i.pfsOffset} size=${i.pfsSize}  body: off=${i.bodyOffset} size=${i.bodySize}")
    appendLine("content_type=${i.contentTypeHex} drm=${i.drmHex} flags=${i.flagsHex} date=${i.versionDateHex}")
    appendLine("size guess: ${i.totalSize} trusted=${i.sizeTrusted} (${i.sizeNote})"); appendLine("missing: ${i.missing.joinToString()}")
    appendLine("entries (${i.entries.size}):"); i.entries.forEach { appendLine("  ${PkgFormat.hex(it.id, 4)} ${it.name} off=${it.offset} size=${it.size}${if (it.encrypted) " ENC" else ""}") }
    appendLine("param.sfo:"); i.params.forEach { appendLine("  ${it.first} = ${it.second}") }
}

@Composable fun PkgScreen(ps4Id: String, path: String, nav: NavController) {
    val ps4 = Ps4Repo.get(ps4Id); val ctx = LocalContext.current; val clip = LocalClipboardManager.current
    var reload by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    val state by produceState<PkgState>(PkgState.Loading, ps4Id, path, reload) { value = PkgState.Loading; value = loadPkg(ps4, path, reload > 0) }
    val fileName = path.substringAfterLast('/')
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BackHeader(fileName, nav) {
                (state as? PkgState.Ok)?.let { ok -> IconButton(onClick = { clip.setText(AnnotatedString(report(ok.info, path, ok.size))); Toast.makeText(ctx, tr("Report copied", "تم نسخ التقرير"), Toast.LENGTH_SHORT).show() }) { Ico(R.drawable.ic_copy) } }
                IconButton(onClick = { reload++ }) { Ico(R.drawable.ic_refresh) }
            }
        }
        when (val st = state) {
            is PkgState.Loading -> item { Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(); Dim(tr("Reading the package header over FTP…", "جارٍ قراءة ترويسة الحزمة عبر FTP…")) } }
            is PkgState.Err -> item { Panel {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Ico(R.drawable.ic_error, 24.dp, MaterialTheme.colorScheme.error); Text(st.msg, Modifier.weight(1f)) }
                if (!st.notPkg) Button(onClick = { reload++ }) { Lbl(tr("Try again", "حاول مرة أخرى")) }
            } }
            is PkgState.Ok -> {
                val i = st.info
                item {
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column {
                            st.pic1?.let { b -> Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().height(150.dp).clickable { preview = b }, contentScale = ContentScale.Crop) }
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(92.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                                    val ic = st.icon
                                    if (ic != null) Image(ic.asImageBitmap(), null, Modifier.fillMaxSize().clickable { preview = ic }, contentScale = ContentScale.Crop) else Ico(R.drawable.ic_package, 40.dp)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(i.title ?: fileName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(i.titleId ?: i.contentId, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                }
                            }
                            FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOfNotNull(PkgFormat.categoryLabel(i.category), i.region, i.appVer?.let { "v$it" }, i.systemVer?.let { "FW ${it.substringBefore(' ')}+" }).forEach { AssistChip(onClick = {}, label = { Lbl(it) }) }
                            }
                        }
                    }
                }
                if (path.lowercase().endsWith(".pkg") && i.totalSize != null && st.size >= i.totalSize && ps4 != null && PkgInspector.httpOn(ps4))
                    item { Button(onClick = { confirmInstall = true }, Modifier.fillMaxWidth()) { Ico(R.drawable.ic_install, 20.dp); Spacer(Modifier.width(8.dp)); Lbl(tr("Install on the PS4", "تثبيت على الـPS4")) } }
                if (i.missing.isNotEmpty()) item { Panel {
                    Text(tr("Some parts are not available yet", "بعض الأجزاء غير متاحة بعد"), fontWeight = FontWeight.SemiBold)
                    Dim(tr("The file may still be downloading, so these parts have not been written yet: ", "قد يكون الملف قيد التحميل لذا لم تُكتب هذه الأجزاء بعد: ") + i.missing.joinToString())
                } }
                item { SelectionContainer { Panel {
                    SectionTitle(tr("Package", "الحزمة"))
                    KV("Content ID", i.contentId)
                    KV(tr("File on PS4 now", "الملف على الـPS4 الآن"), Fmt.bytes(st.size) + (i.totalSize?.takeIf { i.sizeTrusted && it > 0 }?.let { "  (${(st.size * 100 / it).coerceIn(0, 100)}%)" } ?: ""))
                    KV(tr("Total size", "الحجم الكلي"), i.totalSize?.let { Fmt.bytes(it) + "  — " + Tx.t(i.sizeNote) } ?: tr("not readable", "غير قابل للقراءة"))
                    KV(tr("Content type", "نوع المحتوى"), i.contentTypeHex); KV("DRM", i.drmHex); KV(tr("Flags", "الأعلام"), i.flagsHex); KV(tr("Version date", "تاريخ الإصدار"), i.versionDateHex)
                    KV(tr("Body", "الجسم"), "${PkgFormat.hex(i.bodyOffset)}  +  ${Fmt.bytes(i.bodySize)}")
                    KV(tr("PFS image", "صورة PFS"), "${PkgFormat.hex(i.pfsOffset)}  +  ${Fmt.bytes(i.pfsSize)}")
                    KV(tr("Entries", "العناصر"), "${i.entryCount}")
                } } }
                if (st.icon != null || st.pic0 != null || st.pic1 != null) item { Panel {
                    SectionTitle(tr("Artwork", "الصور"))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(st.icon, st.pic0, st.pic1).forEach { b -> if (b != null) Image(b.asImageBitmap(), null, Modifier.height(130.dp).clip(RoundedCornerShape(14.dp)).clickable { preview = b }) }
                    }
                } }
                if (i.params.isNotEmpty()) item { SelectionContainer { Panel {
                    SectionTitle(tr("Parameters (param.sfo)", "المعلمات (param.sfo)"))
                    (if (showAll) i.params else i.params.take(14)).forEach { KV(it.first, it.second) }
                    if (i.params.size > 14) TextButton(onClick = { showAll = !showAll }) { Lbl(if (showAll) tr("Show less", "عرض أقل") else tr("Show all (${i.params.size})", "عرض الكل (${i.params.size})")) }
                } } }
                item {
                    SectionTitle(tr("Files inside the package", "الملفات داخل الحزمة") + " (${i.entries.size})")
                    OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), shape = MaterialTheme.shapes.large,
                        leadingIcon = { Ico(R.drawable.ic_search, 20.dp) }, placeholder = { Text(tr("Filter by name", "تصفية بالاسم")) })
                }
                val shown = i.entries.filter { query.isBlank() || it.name.contains(query, true) }.sortedBy { it.offset }
                itemsIndexed(shown) { _, e ->
                    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Ico(if (e.encrypted) R.drawable.ic_lock else R.drawable.ic_file, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(e.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Dim("${PkgFormat.hex(e.id, 4)}  •  ${Fmt.bytes(e.size)}  •  @${PkgFormat.hex(e.offset)}", maxLines = 1)
                            }
                        }
                    }
                }
                item { Dim(tr("Names marked with a hex id come from the package's own table. Encrypted entries are listed but never decrypted. Layout offsets follow the community PKG format notes and are shown raw so a wrong assumption is visible.",
                    "الأسماء ذات المعرّف السداسي من جدول الحزمة نفسها. العناصر المشفرة تُعرض ولا تُفك. إزاحات التخطيط تتبع ملاحظات المجتمع عن صيغة PKG وتُعرض كما هي ليظهر أي افتراض خاطئ.")) }
            }
        }
    }
    if (confirmInstall && ps4 != null) ConfirmInstall(ps4, listOf(path), close = { confirmInstall = false })
    preview?.let { b -> Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().clickable { preview = null }, contentScale = ContentScale.Fit) } }
}
