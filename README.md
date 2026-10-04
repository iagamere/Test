# PS4 Download Monitor 2.0

Build: open in Android Studio (Koala or newer, JDK 17), sync Gradle, Run / Build APK.
NOT yet compiled or tested by the author of this refactor (no Android SDK was available) — expect to fix a few compile errors on first sync.

Setup: Home -> Add PS4 (IP, ezRemote web port 8080, FTP port 2121 or 0 to disable) -> Test connection -> Save.
Send downloads: Browser -> find link (or ⋮ -> "Send this page's link to PS4") -> choose PS4 / destination -> Send.
Request accepted != download started != download completed; Downloads shows only what the PS4 filesystem proves.

Layout (package com.abdo.ps4monitor):
- Models.kt        Ps4, Download, DlState, Link/Reach/Ps4Status, FsEntry, SubmitResult
- Store.kt         settings, bookmarks, Ps4Repo, DownloadRepo (encrypted JSON persistence)
- EzRemote.kt      confirmed ezRemote calls only: /__local__/download_url, /__local__/list; Net.size (server size check)
- Ftp.kt           FTP helper (short-lived listing sessions)
- DownloadMonitor.kt  shared monitor: HTTP list + FTP fallback, file matching, state machine, speed/ETA, verification, recovery
- Notifier.kt / MonitorService.kt   one notification per download id; foreground service
- MainActivity.kt (nav) / Ui.kt / HomeUi.kt / DownloadsUi.kt / BrowserUi.kt / SettingsUi.kt
Old Engine.kt / Sender.kt (template "Learn" workflow) were removed.

## 2.1
- Downloads: per-card Stop/Delete, long-press multi-select (select all, stop, delete). "Stop" = stop monitoring only (no confirmed ezRemote cancel API); "Delete" = remove from list only.
- Material You (phone colours) with purple fallback; rounded surfaces; status/nav bar tinted to the theme.
- All emoji replaced by vector drawables (res/drawable/ic_*.xml, SVG path data); single-line ellipsised labels; FlowRow for button/chip groups.
- Language: Settings -> Language (Phone / English / العربية), RTL layout, translated engine messages (Lang.kt: tr() and Tx).

## 2.2
- Adaptive launcher icon (+ themed/monochrome) and notification small icon.
- "File name on the PS4" field in the send dialog (suggested from the link, `.pkg` appended if there is no real extension). Optional switch "Send the file name to ezRemote" sends dest as `<folder>/<name>` — EXPERIMENTAL: ezRemote's handling of a file path in `dest` is not confirmed from source.

## 2.3
- PKG reader (Pkg.kt): reads the header, entry table, param.sfo and icon0/pic0/pic1 of a PS4 .pkg over FTP (partial reads, works on a .tmp while it downloads). Shows title, icon, title id, version, FW, region, entries (encrypted ones listed, never decrypted).
  Layout offsets come from community PKG notes, NOT from your files yet; they are range-checked and shown raw. "Copy report" in the inspector exports everything for debugging.
- Total size from the PKG header is applied automatically only when two header fields agree (pkg_size == PFS image end, +-1 MiB); otherwise it is shown but not used.
- Files screen (Home -> Files): browse the PS4 over FTP (HTTP list as fallback), thumbnails for scanned PKGs, image preview, multi-select.
- Delete ON THE PS4 (FTP DELE/RMD): Files screen, Home (untracked .tmp), Downloads ("Also delete the file(s) from the PS4"). Re-lists the folder afterwards to confirm. Extra red warning outside the PS4's download folder.
- Stopping a running transfer on the PS4 is NOT possible with confirmed ezRemote APIs; deleting the partial file is best effort and the UI says so.

## 2.4 (after reading ezRemote http_server.cpp)
- /__local__/list envelope confirmed: {"result":[{name,rights,date,size(string),type:"dir"|"file"}]}; "date" now parsed.
- PKG reads go through GET /__local__/downloadFile?path= with a Range header (FTP REST as fallback) => no FTP needed for icons / size / inspector. Range support depends on the ezRemote build (unverified).
- Delete on PS4 uses POST /__local__/remove {"items":[...]} (recursive!) with FTP fallback; always verified by re-listing; refuses top-level folders.
- Finished PKG without ".pkg" is renamed via POST /__local__/rename {"item","newItemPath"} after a magic check; verified by listing (ezRemote ignores the rename result). Toggle in Settings > Advanced.
- "Send file name in dest" is now OFF by default: how the internal downloader treats dest_path is not in http_server.cpp.
- Never used: GET /stop (stops the whole ezRemote web server).

## 2.5
- Artwork now loads by itself everywhere (Files rows, Downloads cards/detail, Home): PkgThumbs reads the PKG header on demand, one read at a time, disk-cached, failures remembered (no repeated hits while scrolling). Old/completed downloads get their icon and title the first time they are shown.

## 3.0 — everything http_server.cpp offers that makes sense for a download companion
- Install a .pkg on the PS4 (POST /__local__/install) from Files, the PKG inspector and finished downloads; optional auto-install (off by default).
- Install directly from a link (POST /__local__/install_url, enable_rpi, no disk copy) from the send dialog.
- Files: new folder (createFolder), rename (rename), copy / cut / paste (copy, move) with overwrite warning, extract zip/rar/7z (extract), delete (remove), save a PS4 file to the phone (downloadFile via DownloadManager).
- Upload from the phone (multipart /__local__/upload, 8 MiB chunks, resume via uploadResumeSize, size verified at the end).
- Text viewer/editor (getContent / edit, <= 256 KB).
- uploadResumeSize is used as a cheap single-file size query.
- Long operations (Ops.kt) run in an app-level scope with the foreground service; one at a time; results verified by listing because ezRemote ignores several return values.
- Deliberately NOT used: GET /stop (stops ezRemote), POST /compress (writes to its own folder, ignores `destination`, can answer twice), copy with `singleFilename` (only handles folders), /permission (unsupported).

## 3.1 — ezRemote history file (bg_download_history.json)
- BgHistory.kt reads ezRemote's own queue file (web getContent first, FTP fallback). The path is found automatically in /data (or set it in Settings > Advanced).
- Exact total size (file_size) now replaces the guessed size; the download detail shows file size, bytes recorded by ezRemote, failed_attempts, state and id.
- New state "Paused": shown when failed_attempts >= the limit (default 5, configurable) and the file is not growing.
- Pause / Resume buttons (download detail): write failed_attempts = 5 / 1 into that single entry only (text-level patch, other bytes untouched), verify by reading again, keep a backup copy on the phone (files/bg_download_history.backup.json).
- NOT compiled or tested here. Unknown until tested on your PS4: whether ezRemote notices a value written while it is running, and the meaning of the "state" numbers. The log (Settings > Advanced > Debug log) records every read/write.
