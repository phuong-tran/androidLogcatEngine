# Changelog

All notable project changes are documented here.

## 1.4 - 2026-07-31

- Hardened `LogcatSession.stop()` and `stopAndJoin()` so stale session handles cannot stop a newer process-wide capture.
- Published the static Maven artifact as `io.github.phuongtran:logcat-engine-core:1.4`.

## 1.3 - 2026-07-30

- Fixed service-hosted `updateFilters(tags, regex)` so tag changes restart capture instead of being silently ignored.
- Clarified that `LogcatConfig.tags` is a complete logcat tag filter spec; `minLevel` is applied only when `tags` is blank.
- Updated service/AIDL docs for hot-swapped regex filters versus tag-filter restarts.
- Published the static Maven artifact as `io.github.phuongtran:logcat-engine-core:1.3`.

## 1.2 - 2026-07-30

- Prepared the publishable `:core` Android AAR under `maven/` as `io.github.phuongtran:logcat-engine-core:1.2`.
- Added the typed `LogcatEngine`, `LogcatSession`, `LogcatConfig`, `LogFilter`, `LogLevel`, and `LogLine` core API.
- Hardened native lifecycle behavior with eventfd-based shutdown, filter snapshots, bounded line accumulation, and nonblocking all-or-drop pipe writes.
- Added bounded in-memory history, raw and parsed streams, JSON Lines/Text export, and explicit logcat buffer clear results.
- Refined the sample app with collapsible controls, simulator opt-in, copy/share, confirmed buffer clearing, whole-screen scrolling, and back-to-top.
- Added project documentation for scope, FAQ, testing, and the runtime delivery contract.
