# Changelog

All notable project changes are documented here.

## 1.2 - 2026-07-30

- Prepared the publishable `:core` Android AAR under `maven/` as `io.github.phuongtran:logcat-engine-core:1.2`.
- Added the typed `LogcatEngine`, `LogcatSession`, `LogcatConfig`, `LogFilter`, `LogLevel`, and `LogLine` core API.
- Hardened native lifecycle behavior with eventfd-based shutdown, filter snapshots, bounded line accumulation, and nonblocking all-or-drop pipe writes.
- Added bounded in-memory history, raw and parsed streams, JSON Lines/Text export, and explicit logcat buffer clear results.
- Refined the sample app with collapsible controls, simulator opt-in, copy/share, confirmed buffer clearing, whole-screen scrolling, and back-to-top.
- Added project documentation for scope, FAQ, testing, and the runtime delivery contract.
