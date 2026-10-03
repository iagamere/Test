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

## 2.4
- Library tab (LibraryUi.kt): an index of the .pkg files on the PS4 (folder + sub-folders up to 3 levels, default = the PS4's download folder; change it with the folder button). Saved on the phone, refreshed with the refresh button.
- Alphabetical list with letter headers and a quick-jump letter strip; Arabic and Latin are grouped separately (the app language decides which comes first).
- Side rail by type: Games / Updates / DLC / Other / Unscanned. The type comes from the PKG's own param.sfo CATEGORY (read by Pkg.kt); files never read show as Unscanned until you press the image button (it reads title, icon and type over FTP, one file at a time).
- Flexible search: every word may match as a prefix, a part of a word, or with 1-2 typos; it also matches the file name and the title id.
- Tapping an item opens the existing PKG inspector. NOT compiled or tested yet (no Android SDK here); expect to fix a few compile errors on first sync.
