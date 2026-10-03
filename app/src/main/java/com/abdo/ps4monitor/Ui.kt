@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Navigation route of the PKG inspector (path is URL-encoded because it contains slashes). */
fun pkgRoute(ps4Id: String, path: String) = "pkg/${Uri.encode(ps4Id)}/${Uri.encode(path)}"

object Inbox { val url = mutableStateOf<String?>(null) }    // non-null => "Download to PS4" dialog is open

// ---------- small building blocks ----------
/** Vector (SVG-derived) icon. */
@Composable fun Ico(@DrawableRes id: Int, size: Dp = 24.dp, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) =
    Icon(painterResource(id), null, modifier.size(size), tint)

/** Single-line label that is ellipsised instead of breaking into a second line (buttons, chips, tabs). */
@Composable fun Lbl(t: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified,
                    weight: FontWeight? = null, textAlign: TextAlign? = null) =
    Text(t, modifier, color = color, fontWeight = weight, textAlign = textAlign, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style)

@Composable fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }

@Composable fun Dim(t: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(t, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable fun EmptyState(@DrawableRes icon: Int, text: String) =
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Ico(icon, 48.dp, MaterialTheme.colorScheme.outline); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }

fun stateIcon(s: DlState) = when (s) {
    DlState.COMPLETED -> R.drawable.ic_check_circle
    DlState.FAILED, DlState.CONNECTION_LOST -> R.drawable.ic_error
    DlState.NOT_STARTED, DlState.STALLED -> R.drawable.ic_warning
    DlState.STOPPED -> R.drawable.ic_stop
    DlState.VERIFYING -> R.drawable.ic_search
    DlState.DOWNLOADING -> R.drawable.ic_download
    else -> R.drawable.ic_schedule
}
@Composable fun stateColors(s: DlState): Pair<Color, Color> { val c = MaterialTheme.colorScheme; return when (s) {
    DlState.COMPLETED -> c.tertiaryContainer to c.onTertiaryContainer
    DlState.FAILED, DlState.NOT_STARTED -> c.errorContainer to c.onErrorContainer
    DlState.STALLED, DlState.CONNECTION_LOST -> c.secondaryContainer to c.onSecondaryContainer
    DlState.STOPPED -> c.surfaceContainerHighest to c.onSurfaceVariant
    else -> c.primaryContainer to c.onPrimaryContainer } }

fun resultText(r: SubmitResult) = Tx.t(when (r) {
    is SubmitResult.Accepted -> tr("Request accepted. Waiting for the PS4 to start the download…", "قُبل الطلب. بانتظار أن يبدأ الـPS4 التحميل…")
    is SubmitResult.Rejected -> r.message
    is SubmitResult.Unreachable -> r.message
    is SubmitResult.Duplicate -> r.message
    is SubmitResult.Invalid -> r.message
})

/** Real state only: no percentage or ETA unless the total size is actually known. */
@Composable fun DownloadCard(d: Download, onOpen: () -> Unit, onLong: (() -> Unit)? = null, selected: Boolean? = null,
                             onStop: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val ps4 = Ps4Repo.get(d.ps4Id)
    val (bg, fg) = stateColors(d.state)
    val shape = MaterialTheme.shapes.large
    val container = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    Card(Modifier.fillMaxWidth().clip(shape).combinedClickable(onClick = onOpen, onLongClick = onLong), shape = shape, colors = CardDefaults.cardColors(containerColor = container)) {
        Row(Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val art by produceState<Bitmap?>(null, d.id, d.iconReady) { value = if (d.iconReady) withContext(Dispatchers.IO) { PkgStore.bitmap("d:${d.id}", "icon0.png", 160) } else null }
            val pic = art
            Box(Modifier.size(56.dp)) {
                if (pic != null) Image(pic.asImageBitmap(), null, Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Ico(if (selected == true) R.drawable.ic_check else stateIcon(d.state), 26.dp, fg) }
                if (pic != null) Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(bg).border(2.dp, container, CircleShape), contentAlignment = Alignment.Center) {
                    Ico(if (selected == true) R.drawable.ic_check else stateIcon(d.state), 13.dp, fg) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.pkgTitle ?: d.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Dim(listOfNotNull(ps4?.name ?: tr("Removed PS4", "جهاز محذوف"), d.titleId).joinToString("  •  "), maxLines = 1)
                val exp = d.expectedSize?.takeIf { it > 0 }
                if (d.currentSize > 0 || d.state == DlState.COMPLETED)
                    Text(if (exp != null) "${Fmt.bytes(d.currentSize)} / ${Fmt.bytes(exp)}" + (d.pct?.let { "  •  $it%" } ?: "") else Fmt.bytes(d.currentSize),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val f = d.frac
                val bar = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                if (f != null && d.state.active) LinearProgressIndicator(progress = { f }, bar)
                else if (d.state == DlState.DOWNLOADING) LinearProgressIndicator(bar)            // total unknown: indeterminate, no fake %
                if (d.state == DlState.DOWNLOADING) Dim(Fmt.mbs(d.speed) + if (d.etaSec >= 0) "  •  ETA ${Fmt.dur(d.etaSec)}" else "", maxLines = 1)
                Text(d.state.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val extra = d.errorMessage ?: d.note
                if (extra.isNotBlank()) Dim(Tx.t(extra), maxLines = 3)
                if (d.superseded) Dim(tr("Replaced by a newer attempt", "استُبدل بمحاولة أحدث"))
                if (selected == null && !d.superseded && d.sourceUrl.isNotBlank() && d.state in setOf(DlState.NOT_STARTED, DlState.FAILED)) {
                    TextButton(onClick = { scope.launch { Toast.makeText(ctx, resultText(DownloadMonitor.retry(d.id)), Toast.LENGTH_LONG).show() } }) { Lbl(tr("Retry", "إعادة المحاولة")) }
                }
            }
            if (selected == null) {
                if (d.state.active && onStop != null) IconButton(onClick = onStop) { Ico(R.drawable.ic_stop, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
                else if (!d.state.active && onDelete != null) IconButton(onClick = onDelete) { Ico(R.drawable.ic_delete, 22.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable fun Chart(v: List<Float>, m: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(m) {
        if (v.size < 2) return@Canvas
        val mx = (v.max() * 1.2f).coerceAtLeast(0.1f); val dx = size.width / (v.size - 1)
        val path = Path()
        v.forEachIndexed { i, y -> val px = i * dx; val py = size.height * (1 - y / mx); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        drawPath(path, color, style = Stroke(width = 5f))
    }
}

@Composable fun Chips(opts: List<Int>, sel: Int, unit: String, on: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { opts.forEach { FilterChip(selected = it == sel, onClick = { on(it) }, label = { Lbl("$it $unit") }) } }
}
@Composable fun ToggleRow(label: String, key: String, def: Boolean) {
    var v by remember { mutableStateOf(Store.sp.getBoolean(key, def)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f)); Switch(checked = v, onCheckedChange = { v = it; Store.sp.edit().putBoolean(key, it).apply() })
    }
}
@Composable fun BackHeader(title: String, nav: androidx.navigation.NavController, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) { Ico(R.drawable.ic_arrow_back) }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable fun ClipCard() {
    val clip = LocalClipboardManager.current
    var link by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        delay(500)
        val m = Regex("""https?://\S+""").find(clip.getText()?.text.orEmpty())?.value
        if (m != null && m != Store.sp.getString("lastclip", "")) link = m
    }
    val l = link
    if (l != null) Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ico(R.drawable.ic_link, 20.dp); Text(tr("Link in clipboard", "رابط في الحافظة"), fontWeight = FontWeight.SemiBold)
            }
            Text(l, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Store.sp.edit().putString("lastclip", l).apply(); Inbox.url.value = l; link = null }) { Lbl(tr("Download to PS4", "تحميل إلى PS4")) }
                TextButton(onClick = { Store.sp.edit().putString("lastclip", l).apply(); link = null }) { Lbl(tr("Dismiss", "تجاهل")) }
            }
        }
    }
}

/** Direct ezRemote request: choose PS4 -> confirm destination -> send. */
@Composable fun SendDialog(initialUrl: String = "", onAddPs4: () -> Unit, onSent: () -> Unit, close: () -> Unit) {
    val ps4s by Ps4Repo.list.collectAsState()
    var url by remember { mutableStateOf(initialUrl) }
    var sel by remember { mutableStateOf(Ps4Repo.activeId.value ?: ps4s.firstOrNull()?.id) }
    val p = ps4s.firstOrNull { it.id == sel }
    var dest by remember(sel) { mutableStateOf(p?.dest ?: "/data/pkg") }
    var sizeTxt by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialUrl.takeIf { it.startsWith("http") }?.let { Names.fromUrl(it) }.orEmpty()) }
    var nameEdited by remember { mutableStateOf(false) }
    var sendName by remember { mutableStateOf(Store.sp.getBoolean("sendpath", true)) }
    val firstLink = Regex("""https?://\S+""").find(url)?.value
    val linkCount = Regex("""https?://\S+""").findAll(url).count()
    LaunchedEffect(firstLink) { if (!nameEdited) name = firstLink?.let { Names.fromUrl(it) }.orEmpty() }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(tr("Download to PS4", "تحميل إلى PS4")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ps4s.isEmpty()) {
                Text(tr("Add a PS4 first.", "أضف جهاز PS4 أولًا."))
                TextButton(onClick = onAddPs4) { Lbl(tr("Add PS4", "إضافة PS4")) }
            } else {
                if (ps4s.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ps4s.forEach { FilterChip(sel == it.id, { sel = it.id }, label = { Lbl(it.name) }) } }
                else Text("PS4: ${ps4s[0].name}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(url, { url = it }, label = { Text(tr("Link(s), one per line", "الرابط (رابط في كل سطر)")) }, minLines = 2, maxLines = 6)
                OutlinedTextField(dest, { dest = it }, label = { Text(tr("Destination on the PS4", "الوجهة على الـPS4")) }, singleLine = true)
                if (linkCount <= 1) OutlinedTextField(name, { name = it; nameEdited = true }, label = { Text(tr("File name on the PS4", "اسم الملف على الـPS4")) }, singleLine = true,
                    supportingText = { Text(tr("Must end with .pkg to appear in the PS4 package list", "يجب أن ينتهي بـ .pkg ليظهر في قائمة الحزم")) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { Text(tr("Send the file name to ezRemote", "إرسال اسم الملف إلى ezRemote")); Dim(tr("Experimental: sends destination as folder/name. Turn off if downloads stop starting.", "تجريبي: يرسل الوجهة بصيغة مجلد/اسم. أوقفه إن توقفت التحميلات عن البدء.")) }
                    Switch(sendName, { sendName = it; Store.sp.edit().putBoolean("sendpath", it).apply() })
                }
                OutlinedTextField(sizeTxt, { sizeTxt = it }, label = { Text(tr("File size (optional), e.g. 47.5 GB", "حجم الملف (اختياري) مثل 47.5 GB")) }, singleLine = true)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Text(msg, fontWeight = FontWeight.SemiBold)
                Dim(tr("The PS4 downloads the file itself through ezRemote. This app only sends the request and then watches the PS4. Without a size, no percentage or ETA is shown.",
                       "الـPS4 هو من يحمّل الملف عبر ezRemote. التطبيق يرسل الطلب ثم يراقب الـPS4 فقط. بدون الحجم لا تظهر نسبة ولا وقت متبقٍّ."))
            }
        } },
        confirmButton = { TextButton(enabled = !busy && p != null && Regex("""https?://""").containsMatchIn(url), onClick = {
            val target = p ?: return@TextButton
            busy = true; msg = ""
            scope.launch {
                val links = Regex("""https?://\S+""").findAll(url).map { it.value }.toList().distinct().take(10)
                val out = StringBuilder(); var ok = 0
                links.forEachIndexed { i, l ->
                    if (i > 0) delay(4000)
                    val r = DownloadMonitor.submit(target, l, dest, if (links.size == 1) Fmt.parseSize(sizeTxt).takeIf { sizeTxt.isNotBlank() } ?: 0L else 0L,
                        null, if (sendName) (if (links.size == 1) name.trim().ifBlank { Names.fromUrl(l) } else Names.fromUrl(l)) else null, sendName)
                    if (r is SubmitResult.Accepted) ok++
                    out.append(if (links.size > 1) "${i + 1}/${links.size}: " else "").append(resultText(r)).append('\n'); msg = out.toString()
                }
                busy = false
                if (ok == links.size && ok > 0) { Toast.makeText(ctx, tr("Request accepted. Waiting for the PS4 to start…", "قُبل الطلب. بانتظار بدء الـPS4…"), Toast.LENGTH_LONG).show(); close(); onSent() }
            }
        }) { Lbl(tr("Send", "إرسال")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Lbl(tr("Close", "إغلاق")) } })
}
