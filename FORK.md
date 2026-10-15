# Fork notes

Personal fork of Suwayomi-Server, based on tag `v2.4.2366` (the last single-user release).

- `main` = `v2.4.2366` + fork commits. `master` mirrors upstream and isn't built.
- Builds: `.github/workflows/fork.yml` publishes `ghcr.io/paymonr/suwayomi-server:v2.4.2366-fork.r<count>`
  and `:main` (stock `ghcr.io/suwayomi/tachidesk:v2.4.2366` image, pinned by digest, with this jar).
- Fork builds report version `v2.4.2366` (the upstream base, so WebUI compatibility checks stay truthful),
  build type `Stable`, and revision `r<count>-fork`.
- Fork-only schema: `server/src/main/kotlin/suwayomi/tachidesk/server/database/fork/ForkSchema.kt`
  (`fork_*` indexes, idempotent). The stock image still starts on a database this fork has touched.
- Fork-only knob: JVM property `-Dsuwayomi.fork.downloadParallelPages=N` (default 3, 1–8).
- AndroidCompat, extension loading and network interceptors are kept identical to upstream, so
  upstream's extension-compatibility fixes cherry-pick cleanly.

## Batch C (2026-10)

- **F3** — every store's extension list is refreshed every 6 h, first 10 min after start
  (`-Dsuwayomi.fork.extensionRefreshHours=N`, 0 = off), logging installed extensions with updates.
  `-Dsuwayomi.fork.autoUpdateExtensions=true` installs them, after any running library update (up to 3 h). A store
  that can't be reached no longer marks its installed extensions obsolete or deletes its uninstalled entries.
