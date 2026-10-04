@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@Composable private fun NavRow(icon: Int, title: String, sub: String, onClick: () -> Unit) =
    Card(onClick = onClick, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Ico(icon, 24.dp, MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); if (sub.isNotEmpty()) Dim(sub, maxLines = 2) }
            Ico(R.drawable.ic_arrow_forward, 20.dp, MaterialTheme.colorScheme.outline)
        }
    }

@Composable fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val s0 = remember { Store.settings() }
    var iv by remember { mutableIntStateOf(s0.interval) }; var to by remember { mutableIntStateOf(s0.timeout) }
    var st by remember { mutableIntStateOf(s0.stuck) }; var ns by remember { mutableIntStateOf(s0.notStarted) }
    var custom by remember { mutableStateOf("") }
    val pm = ctx.getSystemService(PowerManager::class.java)
    val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text(tr("Settings", "الإعدادات"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item { NavRow(R.drawable.ic_console, tr("PS4 consoles", "أجهزة PS4"), tr("Add, edit and choose the active PS4", "إضافة وتعديل واختيار الجهاز النشط")) { nav.navigate("settings/ps4s") } }
        item { Panel {
            SectionTitle(tr("Language", "اللغة"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to tr("Phone", "الهاتف"), 1 to "English", 2 to "العربية").forEach { (i, t) -> FilterChip(Lang.mode == i, { Store.setLang(i) }, label = { Lbl(t) }) }
            }
            SectionTitle(tr("Appearance", "المظهر"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(tr("System", "النظام"), tr("Light", "فاتح"), tr("Dark", "داكن")).forEachIndexed { i, t -> FilterChip(selected = Store.theme.intValue == i, onClick = { Store.setTheme(i) }, label = { Lbl(t) }) }
            }
            if (Build.VERSION.SDK_INT >= 31) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Text(tr("Use phone colours", "استخدام ألوان الهاتف")); Dim(tr("Follows your wallpaper / system accent colour", "يتبع لون الهاتف الأساسي")) }
                Switch(Store.dynamic.value, { Store.setDynamic(it) })
            }
        } }
        item { Panel {
            SectionTitle(tr("Monitoring", "المراقبة"))
            Text(tr("Polling interval", "فاصل الفحص")); Chips(listOf(2, 3, 5, 10), iv, tr("s", "ث")) { iv = it; Store.putInt("poll", it) }
            Text(tr("Connection timeout", "مهلة الاتصال")); Chips(listOf(5, 10, 20, 30), to, tr("s", "ث")) { to = it; Store.putInt("timeout", it) }
            Text(tr("Stalled after no growth for", "يُعتبر متعثّرًا بعد عدم نمو لمدة")); Chips(listOf(30, 60, 120), st, tr("s", "ث")) { st = it; custom = ""; Store.putInt("stuck", it) }
            OutlinedTextField(custom, { custom = it; it.toIntOrNull()?.takeIf { v -> v >= 10 }?.let { v -> st = v; Store.putInt("stuck", v) } },
                label = { Text(tr("Custom (seconds, min 10) — now $st", "مخصص (ثوانٍ، الحد الأدنى 10) — الآن $st")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text(tr("Report “not started” after", "الإبلاغ «لم يبدأ» بعد")); Chips(listOf(60, 120, 180, 300), ns, tr("s", "ث")) { ns = it; Store.putInt("notstarted", it) }
            Dim(tr("Changes apply on the next poll.", "التغييرات تُطبَّق في الفحص التالي."))
        } }
        item { Panel { SectionTitle(tr("Notifications", "الإشعارات")); ToggleRow(tr("Download notifications", "إشعارات التحميل"), "notif", true) } }
        if (!ignoring) item { Panel {
            Text(tr("Battery optimization may pause background monitoring on some phones.", "قد يوقف توفير البطارية المراقبة في الخلفية على بعض الهواتف."))
            Button(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Lbl(tr("Battery settings", "إعدادات البطارية")) }
        } }
        item { NavRow(R.drawable.ic_search, tr("Advanced", "متقدم"), tr("Debug log, history", "سجل التصحيح، السجل القديم")) { nav.navigate("settings/advanced") } }
        item { Dim("PS4 Download Monitor 3.0") }
    }
}

@Composable fun AdvancedScreen(nav: NavController) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackHeader(tr("Advanced", "متقدم"), nav)
        Panel {
            ToggleRow(tr("Add .pkg automatically to finished PKG files that lack it", "إضافة .pkg تلقائيًا لملفات PKG المكتملة التي تفتقده"), "autopkg", true)
            Dim(tr("Only real PS4 PKG files (checked by their header) are renamed, through ezRemote, and the result is verified.", "تُعاد تسمية ملفات PKG الحقيقية فقط (يُفحص ترويستها) عبر ezRemote ويُتحقق من النتيجة."))
            HorizontalDivider()
            ToggleRow(tr("Install finished PKG files automatically", "تثبيت ملفات PKG المكتملة تلقائيًا"), "autoinstall", false)
            Dim(tr("Off by default. When on, a completed .pkg is sent to the PS4 installer through ezRemote (the PS4 must not be busy).", "متوقف افتراضيًا. عند التفعيل يُرسل ملف .pkg المكتمل إلى مثبّت الـPS4 عبر ezRemote (يجب ألا يكون الـPS4 مشغولًا)."))
            HorizontalDivider()
            ToggleRow(tr("Auto-monitor growing .tmp files this app did not start", "مراقبة ملفات .tmp المتنامية تلقائيًا إن لم يبدأها التطبيق"), "auto", false)
            Dim(tr("Only applies while at least one download is being monitored on that PS4. A .tmp is adopted only after it is seen growing.", "يعمل فقط أثناء مراقبة تحميل واحد على الأقل على ذلك الـPS4، ولا يُعتمد ملف .tmp إلا بعد رؤيته ينمو."))
        }
        NavRow(R.drawable.ic_search, tr("Debug log", "سجل التصحيح"), "") { nav.navigate("settings/log") }
        NavRow(R.drawable.ic_schedule, tr("Download history", "سجل التحميلات"), "") { nav.navigate("settings/history") }
    }
}

@Composable fun LogScreen(nav: NavController) {
    val log by DownloadMonitor.log.collectAsState()
    Column(Modifier.padding(16.dp)) {
        BackHeader(tr("Debug log", "سجل التصحيح"), nav) { TextButton(onClick = { DownloadMonitor.clearLog() }) { Lbl(tr("Clear", "مسح")) } }
        if (log.isEmpty()) Text("(empty)")
        LazyColumn { items(log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) } }     // raw technical text stays English on purpose
    }
}

@Composable fun HistoryScreen(nav: NavController) {
    val all by DownloadRepo.all.collectAsState()
    val fin = all.filter { !it.state.active }.sortedByDescending { it.updatedAt }
    val legacy = remember { Store.legacyHistory() }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { BackHeader(tr("Download history", "سجل التحميلات"), nav) }
        if (fin.isEmpty() && legacy.isEmpty()) item { EmptyState(R.drawable.ic_schedule, tr("Nothing yet.", "لا شيء بعد.")) }
        items(fin, key = { it.id }) { d ->
            Panel { Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.displayName, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Dim("${d.state.label} • ${Fmt.bytes(d.currentSize)} • ${Ps4Repo.get(d.ps4Id)?.name ?: tr("Removed PS4", "جهاز محذوف")}", maxLines = 2)
                    Dim(Fmt.dt(d.completedAt.takeIf { it > 0 } ?: d.updatedAt))
                }
                IconButton(onClick = { DownloadMonitor.remove(d.id) }) { Ico(R.drawable.ic_delete, 22.dp, MaterialTheme.colorScheme.error) }
            } }
        }
        if (legacy.isNotEmpty()) item { SectionTitle(tr("Earlier versions (read-only)", "إصدارات سابقة (للقراءة فقط)")) }
        items(legacy) { o ->
            Panel {
                Text(o.optString("name"), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Dim("${o.optString("status")} • ${Fmt.bytes(o.optLong("size"))} • ${Fmt.dt(o.optLong("end"))}")
            }
        }
    }
}

@Composable fun Ps4ListScreen(nav: NavController) {
    val list by Ps4Repo.list.collectAsState(); val activeId by Ps4Repo.activeId.collectAsState()
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader(tr("PS4 consoles", "أجهزة PS4"), nav)
        if (list.isEmpty()) EmptyState(R.drawable.ic_console, tr("No PS4 added yet.", "لم تُضف أي جهاز PS4 بعد."))
        list.forEach { p ->
            Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p.id == activeId, onClick = { Ps4Repo.setActive(p.id) })
                    Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Dim("${p.host}  •  ${p.dest}", maxLines = 1) }
                    TextButton(onClick = { nav.navigate("settings/ps4/${p.id}") }) { Lbl(tr("Edit", "تعديل")) }
                }
            }
        }
        Button(onClick = { nav.navigate("settings/ps4/new") }) { Ico(R.drawable.ic_add, 18.dp); Spacer(Modifier.width(6.dp)); Lbl(tr("Add PS4", "إضافة PS4")) }
    }
}

@Composable fun Ps4EditScreen(id: String, nav: NavController) {
    val existing = remember(id) { Ps4Repo.get(id) }
    val isNew = existing == null
    var name by remember { mutableStateOf(existing?.name ?: "PS4") }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var http by remember { mutableStateOf((existing?.httpPort ?: 8080).toString()) }
    var ftp by remember { mutableStateOf((existing?.ftpPort ?: 2121).toString()) }
    var user by remember { mutableStateOf(existing?.ftpUser.orEmpty()) }
    var pass by remember { mutableStateOf(existing?.ftpPass.orEmpty()) }
    var dest by remember { mutableStateOf(existing?.dest ?: "/data/pkg") }
    var mode by remember { mutableStateOf(existing?.mode ?: MonitorMode.AUTO) }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun build() = Ps4(id = existing?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim().ifBlank { "PS4" }, host = host.trim(),
        httpPort = http.toIntOrNull() ?: 8080, ftpPort = ftp.toIntOrNull() ?: 2121, ftpUser = user, ftpPass = pass, dest = DownloadMonitor.norm(dest), mode = mode)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader(if (isNew) tr("Add PS4", "إضافة PS4") else tr("Edit PS4", "تعديل PS4"), nav)
        val full = Modifier.fillMaxWidth()
        OutlinedTextField(name, { name = it }, label = { Text(tr("Name (e.g. Living Room)", "الاسم (مثل غرفة المعيشة)")) }, singleLine = true, modifier = full)
        OutlinedTextField(host, { host = it }, label = { Text(tr("PS4 IP address / host", "عنوان IP للـPS4")) }, singleLine = true, modifier = full, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(http, { http = it }, label = { Text(tr("ezRemote web port (default 8080)", "منفذ ويب ezRemote (الافتراضي 8080)")) }, singleLine = true, modifier = full, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(ftp, { ftp = it }, label = { Text(tr("FTP port (default 2121, 0 = off)", "منفذ FTP (الافتراضي 2121، 0 = إيقاف)")) }, singleLine = true, modifier = full, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(user, { user = it }, label = { Text(tr("FTP username (optional)", "اسم مستخدم FTP (اختياري)")) }, singleLine = true, modifier = full)
        OutlinedTextField(pass, { pass = it }, label = { Text(tr("FTP password (optional)", "كلمة مرور FTP (اختياري)")) }, singleLine = true, modifier = full, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(dest, { dest = it }, label = { Text(tr("Default destination", "الوجهة الافتراضية")) }, singleLine = true, modifier = full)
        Text(tr("Monitoring source", "مصدر المراقبة"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(MonitorMode.AUTO to tr("Web + FTP fallback", "ويب + FTP احتياطي"), MonitorMode.HTTP_ONLY to tr("Web only", "ويب فقط"), MonitorMode.FTP_ONLY to tr("FTP only", "FTP فقط"))
                .forEach { (m, t) -> FilterChip(mode == m, { mode = m }, label = { Lbl(t) }) }
        }
        if (busy) LinearProgressIndicator(full)
        if (result.isNotEmpty()) Text(Tx.lines(result), fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && host.isNotBlank(), onClick = { busy = true; result = ""; scope.launch { result = DownloadMonitor.probe(build()); busy = false } }) { Lbl(tr("Test connection", "اختبار الاتصال")) }
            Button(enabled = host.isNotBlank(), onClick = { Ps4Repo.save(build()); nav.popBackStack() }) { Lbl(tr("Save", "حفظ")) }
        }
        if (!isNew) {
            val inUse = DownloadRepo.all.value.any { it.ps4Id == existing!!.id && it.state.active }
            OutlinedButton(enabled = !inUse, onClick = { Ps4Repo.delete(existing!!.id); nav.popBackStack() }) { Lbl(tr("Delete this PS4", "حذف هذا الجهاز"), color = MaterialTheme.colorScheme.error) }
            if (inUse) Dim(tr("Cannot delete while downloads are active on this PS4.", "لا يمكن الحذف أثناء وجود تحميلات نشطة على هذا الجهاز."))
        }
    }
}
