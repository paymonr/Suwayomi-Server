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
