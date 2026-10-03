@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Library = an index of the .pkg files that are ON YOUR PS4 (listed over FTP, or ezRemote's list as fallback).
 * The index is saved on the phone; titles, icons and types come from PkgStore (the PKG reader), so nothing here guesses.
 * Files that were never read show as "Unscanned" until you press the scan button.
 */
private data class LibItem(val path: String, val name: String, val size: Long, val mtime: Long)

private enum class Cat(val en: String, val ar: String, val icon: Int) {
    ALL("All", "الكل", R.drawable.ic_package), GAMES("Games", "ألعاب", R.drawable.ic_console), UPDATES("Updates", "تحديثات", R.drawable.ic_refresh),
    DLC("DLC", "إضافات", R.drawable.ic_add), OTHER("Other", "أخرى", R.drawable.ic_file), UNSCANNED("Unscanned", "لم تُقرأ", R.drawable.ic_info)
}

private fun catOf(i: PkgInfo?) = when (i?.category) { null -> Cat.UNSCANNED; "gd" -> Cat.GAMES; "gp" -> Cat.UPDATES; "ac" -> Cat.DLC; else -> Cat.OTHER }

private sealed class LRow { class Head(val letter: String) : LRow(); class Item(val lib: LibItem) : LRow() }

// ---------- index persistence + scan ----------
private const val MAX_ITEMS = 3000
private const val MAX_DEPTH = 3

private fun loadIndex(ps4Id: String): List<LibItem> = runCatching {
    val a = JSONArray(Store.sp.getString("lib_$ps4Id", "[]"))
    (0 until a.length()).map { a.getJSONObject(it).let { o -> LibItem(o.getString("p"), o.getString("n"), o.getLong("s"), o.optLong("m")) } }
}.getOrDefault(emptyList())

private fun saveIndex(ps4Id: String, l: List<LibItem>) {
    val a = JSONArray(); l.forEach { a.put(JSONObject().put("p", it.path).put("n", it.name).put("s", it.size).put("m", it.mtime)) }
    Store.sp.edit().putString("lib_$ps4Id", a.toString()).apply()
}

private fun rootOf(p: Ps4) = DownloadMonitor.norm(Store.sp.getString("libroot_${p.id}", null) ?: p.dest)

/** Lists [root] and its sub-folders (depth <= 3) and keeps the *.pkg files. A failure in the root is reported; a failure in a sub-folder is skipped. */
private fun scanTree(p: Ps4, root: String, progress: (Int) -> Unit): List<LibItem> {
    val out = ArrayList<LibItem>()
    fun walk(dir: String, depth: Int) {
        if (out.size >= MAX_ITEMS) return
        val l = if (depth == 0) PkgInspector.browse(p, dir) else runCatching { PkgInspector.browse(p, dir) }.getOrNull() ?: return
        for (e in l) {
            if (e.isDir) { if (depth < MAX_DEPTH) walk(joinPath(dir, e.name), depth + 1) }
            else if (e.name.endsWith(".pkg", ignoreCase = true)) out += LibItem(joinPath(dir, e.name), e.name, e.size, e.mtime)
        }
        progress(out.size)
    }
    walk(root, 0)
    return out
}

// ---------- ordering + flexible search ----------
private fun fold(c: Char) = when (c) { 'أ', 'إ', 'آ' -> 'ا'; 'ى' -> 'ي'; 'ة' -> 'ه'; else -> c }
private fun letterOf(t: String): String {
    val c = t.trim().firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return if (c.isDigit()) "#" else fold(c).uppercaseChar().toString()
}
private fun groupOf(l: String): Int { val c = l.first(); val ar = c in '\u0600'..'\u06FF'; val la = c in 'A'..'Z'
    return when { c == '#' -> 0; la -> if (Lang.isAr) 2 else 1; ar -> if (Lang.isAr) 1 else 2; else -> 3 } }

private fun norm(s: String) = s.lowercase().map { fold(it) }.joinToString("").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun lev(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1); cur[0] = i
        for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        prev = cur
    }
    return prev[b.length]
}

/** 0 = no match; higher = closer. Every word of the query must match some word of the text (prefix > contains > up to 1-2 typos). */
private fun score(text: String, q: String): Int {
    val t = norm(text); val qs = norm(q)
    if (qs.isEmpty()) return 1
    if (t.contains(qs)) return 1000 - t.indexOf(qs)
    val words = t.split(' ')
    var total = 0
    for (tok in qs.split(' ')) {
        var best = 0
        for (w in words) {
            val s = when {
                w.startsWith(tok) -> 100
                w.contains(tok) -> 70
                tok.length >= 3 && lev(w.take(tok.length + 1), tok) <= (if (tok.length >= 6) 2 else 1) -> 50
                else -> 0
            }
            if (s > best) best = s
        }
        if (best == 0) return 0
        total += best
    }
    return total
}

// ---------- UI ----------
@Composable private fun LibRow(ps4: Ps4, lib: LibItem, info: PkgInfo?, tick: Int, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val thumb by produceState<Bitmap?>(null, lib.path, lib.size, tick) {
        value = withContext(Dispatchers.IO) { PkgStore.bitmap(PkgStore.key(ps4.id, lib.path, lib.size), "icon0.png", 120) }
    }
    val title = info?.title?.takeIf { t -> t.isNotBlank() } ?: lib.name.removeSuffix(".pkg").removeSuffix(".PKG")
    val sub = listOfNotNull(PkgFormat.categoryLabel(info?.category), (info?.appVer ?: info?.version)?.takeIf { v -> v.isNotBlank() }?.let { v -> "v$v" }, Fmt.bytes(lib.size)).joinToString("  •  ")
    Card(Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick), shape = shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                val b = thumb
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Ico(R.drawable.ic_package, 26.dp, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Dim(sub, maxLines = 1)
            }
        }
    }
}

@Composable fun LibraryScreen(nav: NavController) {
    val ps4s by Ps4Repo.list.collectAsState(); val activeId by Ps4Repo.activeId.collectAsState()
    val ps4 = ps4s.firstOrNull { it.id == activeId }
    if (ps4 == null) {
        Column(Modifier.padding(16.dp)) { Text(tr("Library", "المكتبة"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            EmptyState(R.drawable.ic_console, tr("Add a PS4 first (Settings).", "أضف جهاز PS4 أولًا (من الإعدادات).")) }
        return
    }
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var items by remember(ps4.id) { mutableStateOf(loadIndex(ps4.id)) }
    var busy by remember { mutableStateOf<String?>(null) }          // folder scan progress text
    var scanText by remember { mutableStateOf<String?>(null) }      // PKG-reading progress text
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var cat by remember { mutableStateOf(Cat.ALL) }
    var query by remember { mutableStateOf("") }
    var rail by remember { mutableStateOf(true) }
    var rootDialog by remember { mutableStateOf(false) }
    var root by remember(ps4.id) { mutableStateOf(rootOf(ps4)) }
    val listState = rememberLazyListState()

    fun refresh() {
        if (busy != null) return
        err = null; busy = "0"
        scope.launch {
            try {
                val l = withContext(Dispatchers.IO) { scanTree(ps4, root) { n -> busy = "$n" } }
                items = l; saveIndex(ps4.id, l)
            } catch (e: Exception) { err = Tx.t(PkgInspector.friendly(e)) }
            busy = null
        }
    }
    LaunchedEffect(ps4.id) { if (items.isEmpty()) refresh() }

    // PKG info (title, type, version) already read earlier; read from disk, not from the PS4.
    val infos by produceState<Map<String, PkgInfo?>>(emptyMap(), items, tick) {
        value = withContext(Dispatchers.IO) { items.associate { it.path to PkgStore.load(PkgStore.key(ps4.id, it.path, it.size)) } }
    }

    fun scanDetails() {
        if (!PkgInspector.ftpOn(ps4)) { Toast.makeText(ctx, tr("Reading PKG files needs FTP.", "قراءة ملفات PKG تحتاج FTP."), Toast.LENGTH_SHORT).show(); return }
        val todo = items.filter { infos[it.path]?.hasIcon != true }
        if (todo.isEmpty()) { Toast.makeText(ctx, tr("Everything is already read.", "كل الملفات مقروءة مسبقًا."), Toast.LENGTH_SHORT).show(); return }
        scanJob?.cancel()
        scanJob = scope.launch {
            todo.forEachIndexed { i, e ->
                scanText = "${i + 1}/${todo.size}"
                val key = PkgStore.key(ps4.id, e.path, e.size)
                PkgInspector.mutex.withLock { withContext(Dispatchers.IO) { runCatching { PkgInspector.inspect(ps4, e.path, e.size, key, false) } } }
                if ((i + 1) % 5 == 0 || i == todo.lastIndex) tick++
            }
            scanText = null; tick++
        }
    }

    val counts = remember(items, infos) { Cat.values().associateWith { c -> if (c == Cat.ALL) items.size else items.count { catOf(infos[it.path]) == c } } }
    fun titleOf(i: LibItem) = infos[i.path]?.title?.takeIf { it.isNotBlank() } ?: i.name.removeSuffix(".pkg").removeSuffix(".PKG")

    val q = query.trim()
    val rows: List<LRow> = remember(items, infos, cat, q) {
        val base = items.filter { cat == Cat.ALL || catOf(infos[it.path]) == cat }
        if (q.isNotEmpty()) {
            base.map { i -> i to maxOf(score(titleOf(i), q), score(i.name, q), infos[i.path]?.titleId?.let { t -> score(t, q) } ?: 0) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.map { LRow.Item(it.first) }
        } else {
            val sorted = base.sortedWith(compareBy<LibItem>({ groupOf(letterOf(titleOf(it))) }, { norm(titleOf(it)) }))
            val out = ArrayList<LRow>(); var last = ""
            sorted.forEach { i -> val l = letterOf(titleOf(i)); if (l != last) { out += LRow.Head(l); last = l }; out += LRow.Item(i) }
            out
        }
    }
    val letters = rows.filterIsInstance<LRow.Head>().map { it.letter }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { rail = !rail }) { Ico(R.drawable.ic_tabs) }
            Column(Modifier.weight(1f)) {
                Text(tr("Library", "المكتبة"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Dim("${ps4.name}  •  ${items.size} " + tr("package(s)", "حزمة"), maxLines = 1)
            }
            if (scanText != null) { Lbl(scanText.orEmpty(), style = MaterialTheme.typography.labelMedium); IconButton(onClick = { scanJob?.cancel(); scanText = null }) { Ico(R.drawable.ic_close, 20.dp) } }
            else IconButton(onClick = { scanDetails() }) { Ico(R.drawable.ic_image) }
            IconButton(onClick = { rootDialog = true }) { Ico(R.drawable.ic_folder) }
            IconButton(onClick = { refresh() }, enabled = busy == null) { Ico(R.drawable.ic_refresh) }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Lbl(tr("Search (typos are fine)", "ابحث (الأخطاء الإملائية مقبولة)")) },
            leadingIcon = { Ico(R.drawable.ic_search, 20.dp) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Ico(R.drawable.ic_close, 20.dp) } },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search))
        if (busy != null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)); Dim(tr("Listing ${root}…  ${busy} found", "جارٍ فحص ${root}…  ${busy} وُجدت"), Modifier.padding(horizontal = 16.dp)) }
        err?.let { Dim(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        Row(Modifier.fillMaxSize()) {
            if (rail) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Cat.values().forEach { c ->
                    NavigationRailItem(selected = cat == c, onClick = { cat = c },
                        icon = { BadgedBox(badge = { val n = counts[c] ?: 0; if (n > 0) Badge { Text(if (n > 99) "99+" else "$n") } }) { Ico(c.icon) } },
                        label = { Lbl(tr(c.en, c.ar), style = MaterialTheme.typography.labelSmall) })
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (rows.isEmpty() && busy == null) EmptyState(R.drawable.ic_package,
                    if (items.isEmpty()) tr("No .pkg files found in $root. Use the folder button to change the folder.", "لا توجد ملفات .pkg في $root. غيّر المجلد بزر المجلد.")
                    else tr("Nothing matches.", "لا نتائج."))
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 8.dp, end = if (letters.size > 3 && q.isEmpty()) 28.dp else 8.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { r ->
                        when (r) {
                            is LRow.Head -> stickyHeader(key = "h_" + r.letter) {
                                Text(r.letter, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            is LRow.Item -> item(key = r.lib.path) { LibRow(ps4, r.lib, infos[r.lib.path], tick) { nav.navigate(pkgRoute(ps4.id, r.lib.path)) } }
                        }
                    }
                }
                if (letters.size > 3 && q.isEmpty()) Column(Modifier.align(Alignment.CenterEnd).width(24.dp).fillMaxHeight().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    letters.forEach { l ->
                        Text(l, Modifier.clickable { scope.launch { listState.scrollToItem(rows.indexOfFirst { x -> x is LRow.Head && x.letter == l }.coerceAtLeast(0)) } }.padding(vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (rootDialog) {
        var t by remember { mutableStateOf(root) }
        AlertDialog(onDismissRequest = { rootDialog = false }, title = { Text(tr("Folder to index", "المجلد المراد فهرسته")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(t, { t = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Dim(tr("Its sub-folders (up to 3 levels) are included.", "تُفحص أيضًا المجلدات الفرعية (حتى 3 مستويات)."))
            } },
            confirmButton = { TextButton(onClick = {
                val n = DownloadMonitor.norm(t); root = n; Store.sp.edit().putString("libroot_${ps4.id}", n).apply(); rootDialog = false; refresh()
            }) { Lbl(tr("Save and scan", "حفظ وفحص")) } },
            dismissButton = { TextButton(onClick = { rootDialog = false }) { Lbl(tr("Cancel", "إلغاء")) } })
    }
}
