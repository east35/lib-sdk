# Reader Shell — local-first WebView client for manga-dl + ebook-library

**Status:** design agreed 2026-06-22, no code yet. This doc is self-contained so it can be
worked from any machine (the planning happened in a Claude session on the NAS; this spec is the
handoff). Build happens in **Android Studio on the Mac**; sideload APKs to the Boox.

---

## 1. Objective

Read manga and ebooks **on any device**, **local-first with cloud fallback**, with reading
**progress synced** — the Netflix/Spotify model. The Android e-ink readers (Boox) are the core
devices and are **sometimes offline**.

Decomposition of the goal:
- **Any device *with* network** is already solved: the two existing web apps (manga-dl,
  ebook-library) are served publicly over a Cloudflare tunnel and work in any browser. Nothing to
  build there.
- **The only gap** is on the Android readers: (a) work with **zero network**, and (b) prefer
  **local files** when present. That gap is what this project fills.

## 2. The backends (deployed, UNCHANGED — reference only)

Both are Flask apps on the Synology NAS, reached publicly via **cloudflared tunnel**
(tunnel id `070b0a92-8f3f-4d46-8f6e-21752f4770de`; public hostnames are managed in the Cloudflare
zero-trust dashboard). **No backend changes are required for this project.**

| | manga-dl | ebook-library |
|---|---|---|
| Public URL (cloudflared) | `<FILL: manga public hostname>` | `<FILL: ebook public hostname>` |
| LAN URL | `http://192.168.4.2:8780/` | `http://192.168.4.2:8781/` |
| Auth env | `MANGA_DL_PASSWORD` (password-only) | `EBOOK_LIB_PASSWORD` (password-only) |
| Content unit | a page image extracted from a `.cbz` | the whole `.epub` file |
| Progress key | `(series, chapter.cbz)` + page index | `book_id` + CFI + percent |

Both enforce auth via `@app.before_request`: an unauthenticated `/api/*` request returns
`401 {"ok":false,"error":"auth required"}`. `/login` is a form POST that sets a session cookie.

## 3. Architecture

**Evolve the existing WebView shell into a local-first localhost proxy.** Keep both web UIs
**unchanged** — they stay the universal client. On the Android readers, the shell's WebView loads
`http://127.0.0.1:PORT/` instead of the cloud URL. A tiny embedded HTTP server (e.g. NanoHTTPD)
is the brain that decides, per request, **local vs cloud vs offline-queue**.

```
 ┌─────────────────────── Android shell (APK) ───────────────────────┐
 │  WebView  ──HTTP──▶  localhost:PORT  (embedded proxy)             │
 │                          │                                        │
 │      ┌───────────────────┼────────────────────────┐              │
 │  bundled UI assets   local index {id→path}    progress queue      │
 │  (offline UI)        (scans local root)        (SQLite)           │
 │                          │                                        │
 └──────────────────────────┼────────────────────────────────────────┘
                            ▼  (when online, with session cookie)
                     cloudflared ──▶ Flask backend on NAS
```

Why a **localhost server** and not WebView `shouldInterceptRequest`: the intercept hook **cannot
read POST request bodies**, and progress saves are `POST /api/progress` with a body. A real
localhost server sees full GET+POST and can queue them. (Note: `http://127.0.0.1` is treated as a
secure context by WebView, so fetch/modules/foliate-js work normally.)

### Project structure (all in git)
- `core/` — shared Android library module: the embedded server, local index, progress queue,
  auth/cookie jar, network reachability. **No app-specific logic.**
- `app-ebook/` — thin app module: config for ebook-library (base URL, content-id scheme = sha256
  relpath, serves whole `.epub`), bundled ebook web UI assets. **Evolve the existing shell into
  this.**
- `app-manga/` — thin app module (net-new): config for manga-dl, content-id scheme = path-based,
  must crack `.cbz` locally to serve page images, bundled manga web UI assets.

Two separate APKs / app icons on the Boox (separate local roots, separate cloud URLs), sharing
`core/`.

## 4. The content-ID ↔ local-file mapping ("the file path connection", automatic)

The shell points at **one local root folder per app** (the only user-set config). The local tree
**mirrors the server library's relative layout exactly** (confirmed: Jim's existing downloads are
identical structure + filenames). On launch (and on rescan) the shell walks the root, computes
each file's content id with the **same function the server uses**, and builds an in-memory map
`{content_id → local absolute path}`. No per-file setup, no manifest.

**Offline-availability is presence-based, not download-state:** a library item is "available
offline" iff its id is in the local map. Files already on the device light up automatically.

### ebook (confirmed from `ebook-library/library.py`)
```
book_id = sha256(library_relative_posix_path).hexdigest()[:16]
```
Reproduce verbatim in Kotlin: relativize local file against local root → POSIX string →
SHA-256 → first 16 hex chars. Exact match to server.

### manga (confirmed from `manga-dl/app.py`)
- Content unit is **path-based**, no hash: the API path itself carries it —
  `/api/series/<name>/chapters/<chapter>/page/<idx>`.
- Local chapter file = `<localRoot>/<series>/<chapter>` (a `.cbz`).
- **Page index alignment is critical** (progress is a page number): the proxy MUST reproduce the
  server's page ordering exactly. Server logic (`cbz_page_names`):
  1. `names = [n for n in zip.namelist() if not n.lower().endswith("comicinfo.xml") and not n.endswith("/")]`
  2. `names.sort()`  (plain lexicographic)
  3. page `idx` → `names[idx]`
- Chapter ordering (`chapter_files`): the sorted list of `*.cbz` in the series dir.
- Optional server feature: `?crop=1` autocrops margins (Pillow). For local serving, either skip
  crop or implement later; not required for correctness.

## 5. Proxy routing tables

### ebook (`app-ebook`)
| WebView request | Online | Offline |
|---|---|---|
| `/`, `*.js`, foliate-js, css | bundled assets | bundled assets |
| `GET /api/library` | fetch cloud, then set `offline:true` on books whose id ∈ local map | last-cached library JSON, annotated from local map |
| `GET /api/book/<id>/file` | local map hit → stream local `.epub`; else cloud | local hit → serve; miss → "not downloaded" |
| `GET /api/book/<id>/cover` | local hit → from local file; else cloud (cache) | local/cached |
| `GET /api/progress` | fetch cloud, merge with local queue by newest `updated` | from local queue |
| `POST /api/progress` | write queue **and** forward to cloud | write queue, mark dirty |

### manga (`app-manga`)
Same shape, but content serving cracks the local `.cbz`:
| WebView request | Online | Offline |
|---|---|---|
| `GET /api/library`, `…/cover`, `…/chapters`, `…/pages` | local if chapter file present, else cloud | local |
| `GET /api/series/<n>/chapters/<c>/page/<idx>` | local `.cbz` present → unzip, sort names per §4, serve `names[idx]`; else cloud | local unzip |
| `GET/POST /api/progress` | merge / forward + queue | queue |

## 6. Offline progress queue + sync

- Every `POST /api/progress` lands in local SQLite **first**, then forwards to cloud if reachable.
  Row: `key, payload(json), updated, dirty`.
- A flush worker pushes `dirty` rows when the network returns; clears the flag on success.
- On launch / reconnect: `GET /api/progress` from cloud, **merge per item by newest `updated`**
  (last-writer-wins). The server already stores `updated` (epoch float for manga;
  `last_opened`/ISO for ebook — confirm field) so the conflict key already exists.

## 7. Auth

The shell owns auth so the WebView never sees a login screen:
- On first run (and on `401`), POST the password to `/login` (manga: `MANGA_DL_PASSWORD`; ebook:
  `EBOOK_LIB_PASSWORD`; username field empty/omitted).
- Persist the session cookie; attach it to every cloud call.
- On `401`, re-login once and retry the request.
- Store the password in Android encrypted prefs / keystore — never in the bundled assets.

## 8. Config (per app)
- Cloud base URL (cloudflared public hostname).
- Local root folder (Android directory picker via SAF, persisted). This *is* the file-path link.
- Password (entered once, kept in keystore).

## 9. Build & deploy
- Android Studio on the Mac. Debug-signed APK, sideload to the Boox.
- Trade-off vs the web apps: no instant deploy — each shell change is rebuild + manual sideload.
  (The web UIs still deploy instantly via `docker-compose up -d --build` on the NAS; the shell
  only changes when the proxy/index/queue logic changes, not when the UI changes.)

## 10. Phase plan
1. **Phase 1 — ebook shell** (evolve the existing WebView app into `app-ebook`): localhost proxy,
   bundle UI assets, local index, local `.epub` serving, presence flagging, auth cookie, progress
   queue + merge. Prove the whole pattern end-to-end on the Boox.
2. **Phase 2 — extract `core/`** from what proved out.
3. **Phase 3 — manga shell** (`app-manga`): reuse `core/`, add the `.cbz` page-extraction serving
   and path-based id scheme.

## 11. Open items to resolve on the Mac
- [ ] Fill in the two cloudflared public hostnames (from the Cloudflare dashboard).
- [ ] Locate the existing ebook WebView shell source; decide repo (one mono-repo with
      `core`/`app-ebook`/`app-manga`, or per-app repos). Push to git either way.
- [ ] Bundle current web UI assets for each app into the APK (and a refresh strategy when the web
      UI changes — e.g. re-copy on build, or fetch-and-cache the asset set).
- [ ] Confirm ebook progress timestamp field name for the merge (`progress.py` uses ISO
      `_now_iso()` / `last_opened`); manga uses epoch `updated`.
- [ ] Verify local root SAF folder gives readable file paths the embedded server can open
      (content URIs vs real paths) — may need MANAGE_EXTERNAL_STORAGE or a real path on the Boox.

## 12. Reference: verified backend facts (as of 2026-06-22)
- manga-dl `app.py`: `/api/library`, `/api/series/<n>/cover`, `/api/series/<n>/chapters`,
  `/api/series/<n>/chapters/<c>/pages`, `/api/series/<n>/chapters/<c>/page/<idx>?crop=1`,
  `GET/POST /api/progress`, `/api/progress/read-through`. Progress shape:
  `series.<name>.chapters.<file> = {page, pages, read, updated}` + `series.<name>.current`.
- ebook-library `app.py`: `/api/library`, `/api/book/<id>/file`, `/api/book/<id>/cover`,
  `GET/POST /api/progress`. `book_id = sha256(relpath)[:16]`. Progress keyed by `book_id`
  with `cfi`/`percent`.
