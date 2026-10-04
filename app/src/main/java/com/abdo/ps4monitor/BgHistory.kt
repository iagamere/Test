package com.abdo.ps4monitor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Access to ezRemote Server's background-download ledger.
 *
 * Path (from ezremote-server source config.h):
 *   #define DATA_PATH "/data/ezremote-client"
 *   #define BG_DOWNLOAD_HISTORY_PATH DATA_PATH "/bg_download_history.json"
 *
 * Official enum (config.h):
 *   STATE_PENDING=0, STATE_DOWNLOADING=1, STATE_RESUMED=2, STATE_FAILED=3, STATE_SUCCESS=4
 *
 * Official max retries for a failed background download is 3 (changelog v2.02).
 * Setting failed_attempts to a high value (we use 5) forces the downloader to stop
 * treating the entry as retryable. Setting it back to 0 lets the next server tick
 * resume (confirmed empirically by users; exact resume behaviour can depend on
 * whether ezRemote Server is still running and whether the entry is still in memory).
 *
 * Race note: ezRemote may rewrite the file while we read/write. We always
 * read → mutate → write the whole array, and treat write failures as soft errors.
 */
object BgHistory {
    const val PATH = "/data/ezremote-client/bg_download_history.json"

    /** Value written into failed_attempts to pause. Official max is 3; 5 is a safe "force stop". */
    const val PAUSE_ATTEMPTS = 5
    /** Value written to allow resume. */
    const val RESUME_ATTEMPTS = 0
    /** Threshold at which we consider the entry paused/stopped by the server. */
    const val PAUSE_THRESHOLD = 3

    // Mirror of ezRemote DownloadState
    const val STATE_PENDING = 0
    const val STATE_DOWNLOADING = 1
    const val STATE_RESUMED = 2
    const val STATE_FAILED = 3
    const val STATE_SUCCESS = 4

    data class Entry(
        val type: Int = 0,
        val url: String = "",
        val username: String = "",
        val password: String = "",
        val srcPath: String = "",
        val destPath: String = "",
        val fileSize: Long = 0,
        val bytesTransferred: Long = 0,
        val state: Int = STATE_PENDING,
        val id: Long = 0,
        val timestamp: Long = 0,
        val failedAttempts: Int = 0,
        val raw: JSONObject = JSONObject()
    ) {
        val isPaused: Boolean get() = failedAttempts >= PAUSE_THRESHOLD || state == STATE_FAILED
        val isSuccess: Boolean get() = state == STATE_SUCCESS
        val isActive: Boolean get() = state == STATE_DOWNLOADING || state == STATE_RESUMED || state == STATE_PENDING
        val progressPct: Int?
            get() = if (fileSize > 0) (bytesTransferred * 100 / fileSize).toInt().coerceIn(0, 100) else null
    }

    fun read(ps4: Ps4, timeoutMs: Int = 12_000): List<Entry> {
        val text = try {
            EzRemote.getContent(ps4, PATH, timeoutMs)
        } catch (e: Exception) {
            DownloadMonitor.d("BgHistory read failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        if (text.isBlank()) return emptyList()
        return parse(text)
    }

    fun parse(text: String): List<Entry> {
        val arr = try {
            when {
                text.trimStart().startsWith("[") -> JSONArray(text)
                else -> {
                    val o = JSONObject(text)
                    o.optJSONArray("result") ?: o.optJSONArray("downloads") ?: o.optJSONArray("items")
                    ?: throw EzError("bg_download_history.json is not a JSON array")
                }
            }
        } catch (e: Exception) {
            if (e is EzError) throw e
            throw EzError("Cannot parse bg_download_history.json: ${e.message}")
        }
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Entry(
                type = o.optInt("type", 0),
                url = o.optString("url"),
                username = o.optString("username"),
                password = o.optString("password"),
                srcPath = o.optString("src_path"),
                destPath = o.optString("dest_path"),
                fileSize = o.optLong("file_size", 0),
                bytesTransferred = o.optLong("bytes_transfered", o.optLong("bytes_transferred", 0)),
                state = o.optInt("state", 0),
                id = o.optLong("id", 0),
                timestamp = o.optLong("timestamp", 0),
                failedAttempts = o.optInt("failed_attempts", 0),
                raw = o
            )
        }
        return out
    }

    /** Match a history entry to one of our Download records. Prefer dest_path, then URL / src_path. */
    fun match(entry: Entry, d: Download): Boolean {
        val dest = entry.destPath.trimEnd('/')
        val ourDest = d.dest.trimEnd('/')
        val ourFinal = d.finalPath?.trimEnd('/')
        val ourTemp = d.tempPath?.trimEnd('/')?.removeSuffix(".tmp")
        if (dest.isNotEmpty()) {
            if (dest.equals(ourFinal, true) || dest.equals(ourTemp, true)) return true
            // dest folder + file name
            val destName = dest.substringAfterLast('/')
            if (destName.isNotEmpty() && (
                    destName.equals(d.fileName, true) ||
                    destName.equals(d.displayName, true) ||
                    destName.equals(d.displayName + ".pkg", true) ||
                    (ourFinal != null && destName.equals(ourFinal.substringAfterLast('/'), true)) ||
                    (ourTemp != null && destName.equals(ourTemp.substringAfterLast('/'), true))
                ) && dest.substringBeforeLast('/').equals(ourDest, true)) return true
            if (dest.startsWith(ourDest, true) && destName.isNotEmpty() &&
                (d.displayName.contains(destName.removeSuffix(".pkg"), true) ||
                 destName.contains(d.displayName.removeSuffix(".pkg"), true))) return true
        }
        if (d.sourceUrl.isNotBlank()) {
            if (entry.url.equals(d.sourceUrl, true)) return true
            if (entry.srcPath.contains(d.sourceUrl.takeLast(40), true)) return true
            if (d.sourceUrl.contains(entry.srcPath.takeLast(40).takeIf { it.length > 8 } ?: "\u0000", true)) return true
        }
        return false
    }

    /**
     * Set failed_attempts on the matching entry and write the whole array back.
     * @return true if an entry was found and written.
     */
    fun setFailedAttempts(ps4: Ps4, d: Download, attempts: Int, timeoutMs: Int = 15_000): Boolean {
        val text = EzRemote.getContent(ps4, PATH, timeoutMs)
        if (text.isBlank()) return false
        val arr = when {
            text.trimStart().startsWith("[") -> JSONArray(text)
            else -> {
                val o = JSONObject(text)
                o.optJSONArray("result") ?: o.optJSONArray("downloads") ?: o.optJSONArray("items")
                ?: return false
            }
        }
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val entry = Entry(
                type = o.optInt("type", 0),
                url = o.optString("url"),
                username = o.optString("username"),
                password = o.optString("password"),
                srcPath = o.optString("src_path"),
                destPath = o.optString("dest_path"),
                fileSize = o.optLong("file_size", 0),
                bytesTransferred = o.optLong("bytes_transfered", o.optLong("bytes_transferred", 0)),
                state = o.optInt("state", 0),
                id = o.optLong("id", 0),
                timestamp = o.optLong("timestamp", 0),
                failedAttempts = o.optInt("failed_attempts", 0),
                raw = o
            )
            if (match(entry, d)) {
                o.put("failed_attempts", attempts)
                // Keep state as FAILED when pausing so the server treats it as non-active;
                // when resuming leave state as-is (server will move it on next tick).
                if (attempts >= PAUSE_THRESHOLD && entry.state != STATE_SUCCESS) {
                    o.put("state", STATE_FAILED)
                }
                found = true
                DownloadMonitor.d("BgHistory: set failed_attempts=$attempts on id=${entry.id} dest=${entry.destPath}")
            }
        }
        if (!found) {
            DownloadMonitor.d("BgHistory: no matching entry for ${d.displayName} (dest=${d.dest})")
            return false
        }
        val r = EzRemote.edit(ps4, PATH, arr.toString())
        if (!r.ok) {
            DownloadMonitor.d("BgHistory write failed: ${r.message}")
            throw EzError(r.message.ifBlank { "Failed to write bg_download_history.json" })
        }
        return true
    }

    fun pause(ps4: Ps4, d: Download): Boolean = setFailedAttempts(ps4, d, PAUSE_ATTEMPTS)
    fun resume(ps4: Ps4, d: Download): Boolean = setFailedAttempts(ps4, d, RESUME_ATTEMPTS)
}
