# Fork notes

Personal fork of Suwayomi-Server, based on tag `v2.4.2366` (the last single-user release).

- `main` = `v2.4.2366` + fork commits. `master` mirrors upstream and isn't built.
- Builds: `.github/workflows/fork.yml` publishes `ghcr.io/paymonr/suwayomi-server:v2.4.2366-fork.r<count>`
  and `:main` (stock `ghcr.io/suwayomi/tachidesk:v2.4.2366` image, pinned by digest, with this jar) from
  `main` only; pull requests build the image without pushing it. The jar bundles the stable WebUI, as
  upstream's releases do. Actions are pinned by commit SHA.
- Fork builds report version `v2.4.2366` (the upstream base, so WebUI compatibility checks stay truthful),
  build type `Stable`, and revision `r<count>-fork`.
- Fork-only schema: `server/src/main/kotlin/suwayomi/tachidesk/server/database/fork/ForkSchema.kt`
  (`fork_*` indexes, idempotent; a failed index is logged and skipped, and an invalid one is rebuilt). The stock
  image still starts on a database this fork has touched.
- Fork-only knob: JVM property `-Dsuwayomi.fork.downloadParallelPages=N` (default 3, 1–8).
- AndroidCompat, extension loading and network interceptors are kept identical to upstream, so
  upstream's extension-compatibility fixes cherry-pick cleanly.

## Batch B (2026-10)

- **F9** — a page that fails with an I/O error, HTTP 5xx or 429 is retried 3× (2/4/8 s) before its chapter fails.
  A 404 fails at once. `Retry-After` isn't read (the network layer stays upstream's).
- **F7** — a downloaded chapter's page rows are deleted; if its download is gone later, the page list is fetched again.
- **F11** — default user agent Chrome 156, override with `-Dsuwayomi.fork.userAgent=…`; CBZ images are STORED
  (ComicInfo.xml deflated); no MySQL-only pool properties.
- **F5** — a subscription's data-loader caches are cleared after every event (fresh `isDownloaded`, no growth).
- **F6** — Jackson writes GraphQL responses straight to the response instead of building one String first. Not
  `ctx.jsonStream`: Javalin pipes that through a 1 KB buffer on another thread, which was ~20× slower on 56 MB.
- **F12** — mutation `pruneNonLibraryManga(input: {excludeMangaIds, dryRun})`, dry run by default. Nothing calls it
  automatically; suwayomi-dm will (Batch E). Series fetched, shown in a browse listing or opened (`manga` query) in
  the last 24 h are kept. Shown/opened is remembered in memory only, so right after a restart only fetch times count.

## Batch C (2026-10)

- **F8** — scheduled library updates check a series less often once it has gone quiet (no new chapter found):
  under 30 days every run, 30–180 days daily, over 180 days every 3 days, counted from its last successful check, so
  a failing series stays due. Manual updates check everything. `-Dsuwayomi.fork.smartUpdate=false` turns it off.
  Each scheduled run logs `Smart update: skipping N quiet series checked recently`.
- **F3** — every store's extension list is refreshed every 6 h, first 10 min after start
  (`-Dsuwayomi.fork.extensionRefreshHours=N`, 0 = off), logging installed extensions with updates.
  `-Dsuwayomi.fork.autoUpdateExtensions=true` installs them, after any running library update (up to 3 h). A store
  that can't be reached no longer marks its installed extensions obsolete or deletes its uninstalled entries.

## Batch D (2026-10)

- **F10a** — Chromium starts on the first WebView request (an extension's WebView or the WebUI's browser view) instead
  of at server start. The installation is still checked, and downloaded if needed, at start.
  `-Dsuwayomi.fork.lazyChromium=false` starts it at server start, as upstream does. The log says
  `Chromium will start on the first WebView request`, then `Starting Chromium for the first WebView request`.

## Batch E (2026-10)

APIs for suwayomi-dm, so it stops reading the whole library, writing to the database and parsing the log.

- **Change stamps** — `manga.fork_changed_at` (epoch ms), kept current by seven `fork_` triggers. PostgreSQL only:
  the triggers are plpgsql, so the stock image keeps the stamps current on a database this has touched, and a
  rollback needs no cleanup. H2 gets no column and no triggers (H2 triggers are Java classes a stock image wouldn't
  have). A series is stamped when one of its listed fields changes (title, url, source, thumbnail, artist, author,
  description, genre, status, in library), and, while it's in the library, when a chapter's listed fields change
  (name, scanlator, number, upload date, downloaded, page count, read, bookmark), a chapter is added or removed, or
  its categories change. Fetch times and reading progress don't count. The chapter trigger's function is
  `SECURITY DEFINER`, so suwayomi-dm's graft role, which may update only two chapter columns, still works. The log
  says `ForkChangeTracking: on`.
- **`MangaType.changedAt`** and the **`changedAt` manga filter** — the stamp, as a LongString. On H2 the field is
  null and the filter fails with "changedAt needs change tracking, which runs on PostgreSQL only".
- **`setChaptersDownloadState(input: { ids, isDownloaded, pageCount })`** — sets chapters' downloaded flag and page
  count without touching files, for downloads placed by another tool (suwayomi-dm's grafts). All or nothing; a
  page count above 0 is required when marking downloaded, and clearing the flag leaves it alone.
- **`DownloadType.failureReason { exception message }`** — why the last attempt failed: the exception's class name
  and message, as the downloader's `failed due to` log line prints them. The log line is unchanged.
