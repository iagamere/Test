package com.abdo.ps4monitor
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Kept for the FTP helper (Ftp.kt). Built from a Ps4 profile. */
data class Conn(val host: String, val port: Int, val user: String, val pass: String)
data class Settings(val interval: Int, val timeout: Int, val stuck: Int, val notStarted: Int,
                    val auto: Boolean, val notif: Boolean)
data class Bookmark(val title: String, val url: String, val icon: String = "")   // icon = base64 PNG

object Store {
    lateinit var sp: SharedPreferences
    lateinit var secure: SharedPreferences      // encrypted: PS4 profiles (incl. FTP passwords) and download records
    val theme = mutableIntStateOf(0)            // 0 system, 1 light, 2 dark
    val dynamic = androidx.compose.runtime.mutableStateOf(true)   // use the phone's own (Material You) colours

    fun init(c: Context) {
        sp = c.getSharedPreferences("app", 0)
        val key = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        secure = EncryptedSharedPreferences.create(c, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        theme.intValue = sp.getInt("theme", 0); dynamic.value = sp.getBoolean("dynamic", true); Lang.mode = sp.getInt("lang", 0)
        Ps4Repo.load(); DownloadRepo.load()
    }
    /** Defaults for the new engine. Old "interval" (1 s) key is intentionally not reused: too aggressive for HTTP. */
    fun settings() = Settings(sp.getInt("poll", 3), sp.getInt("timeout", 10), sp.getInt("stuck", 60),
        sp.getInt("notstarted", 180), sp.getBoolean("auto", false), sp.getBoolean("notif", true))
    fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    fun setLang(i: Int) { Lang.mode = i; putInt("lang", i) }
    fun setDynamic(b: Boolean) { dynamic.value = b; sp.edit().putBoolean("dynamic", b).apply() }
    fun setTheme(i: Int) { theme.intValue = i; putInt("theme", i) }
    fun nextNotifId(): Int { val n = sp.getInt("notifseq", 100) + 1; sp.edit().putInt("notifseq", n).apply(); return n }

    // ----- bookmarks -----
    fun bookmarks(): List<Bookmark> = runCatching {
        val a = JSONArray(sp.getString("bookmarks2", null) ?: legacyBookmarks())
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Bookmark(o.optString("n"), o.optString("u"), o.optString("i")) } }
    }.getOrDefault(emptyList())
    private fun legacyBookmarks(): String {
        val a = runCatching { JSONArray(sp.getString("bookmarks", "[]")) }.getOrDefault(JSONArray())
        val out = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.put(JSONObject().put("n", it.optString("n")).put("u", it.optString("u"))) }
        return out.toString()
    }
    private fun saveBookmarks(l: List<Bookmark>) = sp.edit().putString("bookmarks2",
        JSONArray(l.map { JSONObject().put("n", it.title).put("u", it.url).put("i", it.icon) }).toString()).apply()
    fun addBookmark(b: Bookmark) = saveBookmarks(bookmarks().filter { it.url != b.url } + b)
    fun deleteBookmark(url: String) = saveBookmarks(bookmarks().filter { it.url != url })

    /** v1 history entries (read-only legacy view). */
    fun legacyHistory(): List<JSONObject> = runCatching {
        val a = JSONArray(sp.getString("history", "[]")); (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
}

object Ps4Repo {
    val list = MutableStateFlow<List<Ps4>>(emptyList())
    val activeId = MutableStateFlow<String?>(null)

    fun load() {
        val s = Store.secure.getString("ps4s", null)
        var l = runCatching {
            val a = JSONArray(s ?: "[]")
            (0 until a.length()).map { a.getJSONObject(it).let { o ->
                Ps4(o.getString("id"), o.getString("name"), o.getString("host"), o.optInt("http", 8080), o.optInt("ftp", 2121),
                    o.optString("fu"), o.optString("fp"), o.optString("dest", "/data/pkg"),
                    runCatching { MonitorMode.valueOf(o.optString("mode", "AUTO")) }.getOrDefault(MonitorMode.AUTO)) } }
        }.getOrDefault(emptyList())
        if (s == null) {   // migrate the single v1 connection into a first profile
            val h = Store.secure.getString("host", "").orEmpty()
            if (h.isNotBlank()) {
                l = listOf(Ps4(name = "My PS4", host = h, httpPort = Store.sp.getInt("webport", 8080),
                    ftpPort = Store.secure.getInt("port", 2121), ftpUser = Store.secure.getString("user", "").orEmpty(),
                    ftpPass = Store.secure.getString("pass", "").orEmpty(), dest = Store.sp.getString("dlfolder", "/data/pkg") ?: "/data/pkg"))
                persist(l)
            }
        }
        list.value = l
        activeId.value = Store.sp.getString("activeps4", null)?.takeIf { id -> l.any { it.id == id } } ?: l.firstOrNull()?.id
    }
    private fun persist(l: List<Ps4>) = Store.secure.edit().putString("ps4s", JSONArray(l.map {
        JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("http", it.httpPort).put("ftp", it.ftpPort)
            .put("fu", it.ftpUser).put("fp", it.ftpPass).put("dest", it.dest).put("mode", it.mode.name) }).toString()).apply()
    fun get(id: String?) = list.value.firstOrNull { it.id == id }
    fun active() = get(activeId.value)
    fun setActive(id: String) { activeId.value = id; Store.sp.edit().putString("activeps4", id).apply() }
    fun save(p: Ps4) {
        val l = if (list.value.any { it.id == p.id }) list.value.map { if (it.id == p.id) p else it } else list.value + p
        list.value = l; persist(l); if (activeId.value == null) setActive(p.id)
    }
    fun delete(id: String) {
        val l = list.value.filter { it.id != id }; list.value = l; persist(l)
        if (activeId.value == id) { activeId.value = l.firstOrNull()?.id; Store.sp.edit().putString("activeps4", activeId.value).apply() }
    }
}

/** Persistent download records (encrypted prefs, JSON). Survives UI/service/process death. */
object DownloadRepo {
    val all = MutableStateFlow<List<Download>>(emptyList())
    private var lastSave = 0L

    fun get(id: String) = all.value.firstOrNull { it.id == id }
    fun add(d: Download) { all.update { listOf(d) + it }; flush(true) }
    fun remove(id: String) { all.update { l -> l.filter { it.id != id } }; flush(true) }
    fun update(id: String, f: (Download) -> Download) {
        var structural = false
        all.update { l -> l.map { if (it.id == id) { val n = f(it).copy(updatedAt = System.currentTimeMillis())
            structural = n.state != it.state || n.tempPath != it.tempPath || n.finalPath != it.finalPath || n.terminalNotified != it.terminalNotified
            n } else it } }
        flush(structural)    // size/speed ticks are written at most every 10 s
    }
    fun flush(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 10_000) return
        lastSave = now
        val arr = JSONArray()
        all.value.take(300).forEach { d ->
            arr.put(JSONObject().put("id", d.id).put("ps4", d.ps4Id).put("att", d.attempt).put("retryOf", d.retryOf ?: JSONObject.NULL)
                .put("url", d.sourceUrl).put("name", d.displayName).put("dest", d.dest)
                .put("tmp", d.tempPath ?: JSONObject.NULL).put("fin", d.finalPath ?: JSONObject.NULL)
                .put("exp", d.expectedSize ?: JSONObject.NULL).put("expSrc", d.expectedSource)
                .put("size", d.currentSize).put("speed", d.speed).put("avg", d.avgSpeed).put("peak", d.peakSpeed)
                .put("state", d.state.name).put("note", d.note).put("created", d.createdAt).put("submitted", d.submittedAt)
                .put("started", d.startedAt).put("completed", d.completedAt).put("seen", d.lastSeenAt)
                .put("err", d.errorMessage ?: JSONObject.NULL).put("nid", d.notificationId)
                .put("base", d.baseline?.let { b -> JSONObject().also { o -> b.forEach { (k, v) -> o.put(k, v) } } } ?: JSONObject.NULL)
                .put("fname", d.fileName ?: JSONObject.NULL).put("ptitle", d.pkgTitle ?: JSONObject.NULL).put("ptid", d.titleId ?: JSONObject.NULL).put("icon", d.iconReady).put("sup", d.superseded).put("tn", d.terminalNotified).put("upd", d.updatedAt))
        }
        Store.secure.edit().putString("downloads", arr.toString()).apply()
    }
    fun load() {
        val a = runCatching { JSONArray(Store.secure.getString("downloads", "[]")) }.getOrDefault(JSONArray())
        all.value = (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            fun s(k: String) = if (o.isNull(k)) null else o.getString(k)
            Download(o.getString("id"), o.getString("ps4"), o.optInt("att", 1), s("retryOf"), o.getString("url"),
                o.getString("name"), o.getString("dest"), s("tmp"), s("fin"),
                if (o.isNull("exp")) null else o.getLong("exp"), o.optString("expSrc"),
                o.optLong("size"), o.optDouble("speed", 0.0), o.optDouble("avg", 0.0), o.optDouble("peak", 0.0), -1,
                DlState.valueOf(o.getString("state")), o.optString("note"), o.getLong("created"), o.optLong("submitted"),
                o.optLong("started"), o.optLong("completed"), o.optLong("seen"), s("err"), o.getInt("nid"),
                if (o.isNull("base")) null else o.getJSONObject("base").let { b -> b.keys().asSequence().associateWith { k -> b.getLong(k) } },
                o.optBoolean("sup"), o.optBoolean("tn"), o.optLong("upd", o.getLong("created")), s("ptitle"), s("ptid"), o.optBoolean("icon"), s("fname"))
        }.getOrNull() }
    }
}
