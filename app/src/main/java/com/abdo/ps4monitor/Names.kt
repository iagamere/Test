package com.abdo.ps4monitor
import android.net.Uri

/** File-name suggestion from the link only. No request is made to the server (links like /verify/<token> may be single-use). */
object Names {
    fun clean(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')
    private fun hasExt(n: String): Boolean {
        val e = n.substringAfterLast('.', "")
        return e.length in 1..5 && e.all { it.isLetterOrDigit() } && !e.all { it.isDigit() }
    }
    /** Last path segment, decoded; ".pkg" is appended when there is no real extension. */
    fun fromUrl(u: String): String {
        val raw = runCatching { Uri.decode(u.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')) }.getOrDefault("")
        val n = clean(raw).ifBlank { "download" }
        return if (hasExt(n)) n else "$n.pkg"
    }
}
