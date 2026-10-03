@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.abdo.ps4monitor
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Message
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.UUID

class BTab(val id: String, val web: WebView, val openerId: String?) {
    var title by mutableStateOf("New tab")
    var url by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var canBack by mutableStateOf(false)
    var canFwd by mutableStateOf(false)
    var icon by mutableStateOf<Bitmap?>(null)
}

/** Tabs live here (process-level), not in composition: switching sections or rotating never destroys a page. */
object Browser {
    const val HOME = "https://www.google.com"
    lateinit var app: Context
    val tabs = mutableStateListOf<BTab>()
    var activeId by mutableStateOf<String?>(null)
    fun init(c: Context) { app = c.applicationContext }
    fun current() = tabs.firstOrNull { it.id == activeId }
    fun get(id: String?) = tabs.firstOrNull { it.id == id }

    fun newTab(url: String?, opener: String? = null, select: Boolean = true): BTab {
        val tab = BTab(UUID.randomUUID().toString(), WebView(app), opener)
        setup(tab); tabs.add(tab)
        if (select || activeId == null) activeId = tab.id
        if (url != null) tab.web.loadUrl(url)
        return tab
    }
    fun close(id: String) {
        val i = tabs.indexOfFirst { it.id == id }; if (i < 0) return
        val t = tabs.removeAt(i)
        (t.web.parent as? ViewGroup)?.removeView(t.web); t.web.stopLoading(); t.web.destroy()
        if (activeId == id) activeId = get(t.openerId)?.id ?: tabs.getOrNull(minOf(i, tabs.lastIndex))?.id    // back to the page that opened it
        if (tabs.isEmpty()) newTab(HOME)
    }
    fun save() = Store.sp.edit().putString("tabs", JSONArray(tabs.mapNotNull { it.url.takeIf { u -> u.startsWith("http") } }).toString())
        .putInt("tabsel", tabs.indexOfFirst { it.id == activeId }).apply()
    fun restore() {
        val a = runCatching { JSONArray(Store.sp.getString("tabs", "[]")) }.getOrDefault(JSONArray())
        val sel = Store.sp.getInt("tabsel", 0)
        for (i in 0 until a.length()) newTab(a.getString(i), select = i == sel)
        if (tabs.isEmpty()) newTab(HOME)
    }
    private fun sync(t: BTab) { t.canBack = t.web.canGoBack(); t.canFwd = t.web.canGoForward() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup(tab: BTab) {
        val w = tab.web
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false           // popups without a user gesture are blocked
            loadWithOverviewMode = true; useWideViewPort = true; mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val u = r?.url?.toString().orEmpty()
                return !(u.startsWith("http://") || u.startsWith("https://"))        // swallow intent:/market: etc.; never leave the app
            }
            override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) { if (u != null) tab.url = u; tab.icon = null; sync(tab) }
            override fun onPageFinished(v: WebView?, u: String?) { sync(tab) }
            override fun doUpdateVisitedHistory(v: WebView?, u: String?, isReload: Boolean) { if (u != null) tab.url = u; sync(tab) }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) { tab.progress = p }
            override fun onReceivedTitle(v: WebView?, t: String?) { if (!t.isNullOrBlank()) tab.title = t }
            override fun onReceivedIcon(v: WebView?, icon: Bitmap?) { tab.icon = icon }
            // Links/ads that open a window become a NEW tab; the original page is never replaced.
            override fun onCreateWindow(v: WebView?, isDialog: Boolean, isUserGesture: Boolean, msg: Message?): Boolean {
                val m = msg ?: return false
                val transport = m.obj as? WebView.WebViewTransport ?: return false
                val nt = newTab(null, opener = tab.id, select = isUserGesture)       // gesture-less popups open in the background
                transport.webView = nt.web; m.sendToTarget(); return true
            }
            override fun onCloseWindow(window: WebView?) { tabs.firstOrNull { it.web === window }?.let { close(it.id) } }
        }
        w.setDownloadListener { u, _, _, _, _ ->
            if (u.startsWith("http")) Inbox.url.value = u else Toast.makeText(app, "Unsupported link type", Toast.LENGTH_SHORT).show()
        }
    }
}

fun iconToB64(b: Bitmap?): String {
    if (b == null) return ""
    val s = Bitmap.createScaledBitmap(b, 64, 64, true)
    return ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray().let { Base64.encodeToString(it, Base64.NO_WRAP) }
}
fun b64ToBitmap(s: String): Bitmap? = if (s.isEmpty()) null else runCatching { Base64.decode(s, Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()

/** Clean square favicon container with a vector fallback. */
@Composable fun Favicon(bmp: Bitmap?, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
        else Ico(R.drawable.ic_globe, (size * 0.55f).dp, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BarBtn(icon: Int, enabled: Boolean = true, onClick: () -> Unit) =
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) { Ico(icon, 22.dp) }

@Composable fun BrowserScreen() {
    val ctx = LocalContext.current
    val tab = Browser.current()
    var addr by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showBm by remember { mutableStateOf(false) }
    LaunchedEffect(tab?.id, tab?.url) { if (!focused) addr = tab?.url.orEmpty() }
    BackHandler(enabled = tab != null && (tab.canBack || Browser.get(tab.openerId) != null)) {
        if (tab!!.canBack) tab.web.goBack() else Browser.close(tab.id)
    }
    fun go() {
        val t = addr.trim(); if (t.isEmpty()) return
        val u = if ("://" in t) t else if ("." in t && " " !in t) "https://$t" else "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        Browser.current()?.web?.loadUrl(u)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BarBtn(R.drawable.ic_arrow_back, tab?.canBack == true) { tab?.web?.goBack() }
            BarBtn(R.drawable.ic_arrow_forward, tab?.canFwd == true) { tab?.web?.goForward() }
            BarBtn(if ((tab?.progress ?: 100) < 100) R.drawable.ic_close else R.drawable.ic_refresh) { if ((tab?.progress ?: 100) < 100) tab?.web?.stopLoading() else tab?.web?.reload() }
            TextField(addr, { addr = it }, singleLine = true, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused },
                placeholder = { Lbl(tr("Search or enter address", "ابحث أو أدخل عنوانًا")) },
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }))
            IconButton(onClick = { showTabs = true }, modifier = Modifier.size(40.dp)) {
                Box(Modifier.size(24.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Text("${Browser.tabs.size}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            }
            Box {
                BarBtn(R.drawable.ic_more) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(leadingIcon = { Ico(R.drawable.ic_add, 20.dp) }, text = { Text(tr("New tab", "تبويب جديد")) }, onClick = { menu = false; Browser.newTab(Browser.HOME) })
                    DropdownMenuItem(leadingIcon = { Ico(R.drawable.ic_bookmark, 20.dp) }, text = { Text(tr("Add bookmark", "إضافة إشارة مرجعية")) }, onClick = {
                        menu = false
                        tab?.takeIf { it.url.startsWith("http") }?.let { Store.addBookmark(Bookmark(it.title, it.url, iconToB64(it.icon))); Toast.makeText(ctx, tr("Bookmark saved", "تم حفظ الإشارة"), Toast.LENGTH_SHORT).show() }
                    })
                    DropdownMenuItem(leadingIcon = { Ico(R.drawable.ic_bookmark, 20.dp) }, text = { Text(tr("Bookmarks", "الإشارات المرجعية")) }, onClick = { menu = false; showBm = true })
                    DropdownMenuItem(leadingIcon = { Ico(R.drawable.ic_download, 20.dp) }, text = { Text(tr("Send this page's link to PS4", "إرسال رابط الصفحة إلى PS4")) },
                        onClick = { menu = false; tab?.url?.takeIf { it.startsWith("http") }?.let { Inbox.url.value = it } })
                }
            }
        }
        if ((tab?.progress ?: 100) < 100) LinearProgressIndicator(progress = { (tab?.progress ?: 0) / 100f }, Modifier.fillMaxWidth().height(3.dp))
        if (tab != null) AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { FrameLayout(it) },
            update = { fl ->
                val wv = tab.web
                if (fl.childCount != 1 || fl.getChildAt(0) !== wv) {
                    fl.removeAllViews(); (wv.parent as? ViewGroup)?.removeView(wv)
                    fl.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            }, onRelease = { it.removeAllViews() })
    }

    if (showTabs) AlertDialog(onDismissRequest = { showTabs = false }, title = { Text(tr("Tabs", "التبويبات") + " (${Browser.tabs.size})") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Browser.tabs.toList().forEach { t ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Browser.activeId = t.id; showTabs = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Favicon(t.icon, 36)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (t.id == Browser.activeId) FontWeight.Bold else FontWeight.Normal)
                        Dim(t.url, maxLines = 1)
                    }
                    IconButton(onClick = { Browser.close(t.id) }) { Ico(R.drawable.ic_close, 20.dp) }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { Browser.newTab(Browser.HOME); showTabs = false }) { Lbl(tr("New tab", "تبويب جديد")) } },
        dismissButton = { TextButton(onClick = { showTabs = false }) { Lbl(tr("Close", "إغلاق")) } })

    if (showBm) {
        var bms by remember { mutableStateOf(Store.bookmarks()) }
        AlertDialog(onDismissRequest = { showBm = false }, title = { Text(tr("Bookmarks", "الإشارات المرجعية")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (bms.isEmpty()) Text(tr("No bookmarks yet. Open a site and use ⋮ → Add bookmark.", "لا توجد إشارات بعد. افتح موقعًا ثم ⋮ ← إضافة إشارة مرجعية."))
                bms.forEach { b ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Browser.current()?.web?.loadUrl(b.url); showBm = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Favicon(b64ToBitmap(b.icon), 36)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(b.title.ifBlank { b.url }, maxLines = 1, overflow = TextOverflow.Ellipsis); Dim(b.url, maxLines = 1) }
                        IconButton(onClick = { Store.deleteBookmark(b.url); bms = Store.bookmarks() }) { Ico(R.drawable.ic_delete, 20.dp) }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { showBm = false }) { Lbl(tr("Close", "إغلاق")) } })
    }
}
