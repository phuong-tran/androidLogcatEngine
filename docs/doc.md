# LogcatEngine Technical Notes

## Table Of Contents

- [Why It Exists](#why-it-exists)
- [Scope Boundary](#scope-boundary)
- [When To Use It](#when-to-use-it)
- [When Not To Use It](#when-not-to-use-it)
- [Core Model](#core-model)
- [Runtime Flow](#runtime-flow)
- [Delivery Contract](#delivery-contract)
- [Filtering](#filtering)
- [Sample App Boundary](#sample-app-boundary)
- [Operational Notes](#operational-notes)

Related docs: [FAQ](faq.md) and [testing commands](testing.md).

## Why It Exists

LogcatEngine exists for apps that need app-embedded diagnostic log capture
without requiring Android Studio, `adb logcat`, or a full observability SDK.

Its narrow job is to expose recent logcat context from inside the running app:

- native starts and monitors a `logcat` process
- native applies optional line filtering and writes accepted lines to a pipe
- Kotlin decodes UTF-8 lines and exposes raw and structured streams
- callers can inspect current state, recent history, and export history

The library is useful for internal builds, QA builds, support builds, and
hidden diagnostic screens where recent runtime context is more valuable than a
perfect long-term log archive.

## Scope Boundary

LogcatEngine is intentionally not a telemetry platform.

It does not decide which events are important, upload logs, schedule background
work, enrich records with user/device context, or own product observability.
The app owns those choices.

LogcatEngine only provides a bounded capture boundary:

```text
Android logcat -> native capture/filter -> Kotlin Flow -> app/sample UI/export
```

The app should treat this as local diagnostic context. If logs are later sent to
a backend, that sink should live above LogcatEngine and should apply its own
privacy, sampling, retention, and retry policy.

## When To Use It

Use LogcatEngine when you need:

- an in-app log console for debug or QA builds
- a support screen that can export recent log context
- a small library for local logcat capture without vendor SDK ownership
- structured `Flow<LogLine>` output for a Compose or custom UI
- hot-swappable text or regex filtering
- a bounded history buffer for recent diagnostics

Good fit:

```text
internal build -> start current-process capture -> filter -> inspect/export
```

## When Not To Use It

Do not use LogcatEngine as:

- a crash reporter
- an analytics SDK
- an observability SDK replacement
- a lossless audit log
- a hidden network uploader
- a long-term local database
- a place to collect sensitive raw payloads without app-owned policy

LogcatEngine favors application safety and responsiveness over completeness.
That trade-off should be visible to product and support workflows.

## Core Model

### Engine

`LogcatEngine` is the public facade. `LogcatEngineFactory.create()` returns a
native-backed engine when the native library is available, otherwise a no-op
engine that reports an error state instead of crashing the host app.

### Config

`LogcatConfig` describes the capture request:

- optional process id
- complete tag filter spec passed to `logcat`
- minimum log level, used only when the tag filter spec is blank
- line filter mode
- bounded history size

`LogcatConfig.currentProcess()` is the normal app-embedded starting point.

### Session

`LogcatSession` is the runtime handle returned by `start()`:

- `rawLogs`: raw line stream
- `logs`: parsed `LogLine` stream
- `state`: lifecycle state
- `history()`: bounded recent parsed history
- `updateFilter()`: hot-swap line filter
- `exportHistory()`: text or JSON Lines export
- `stop()` / `stopAndJoin()`: cleanup

The native engine remains process-wide, but session shutdown is
generation-checked so an older handle cannot stop a newer capture that replaced
it.

### LogLine

`LogLine` preserves the raw line and includes parsed fields when the logcat
format is recognized: timestamp, pid, tid, level, tag, and message.

## Runtime Flow

1. App creates an engine with `LogcatEngineFactory.create()`.
2. App starts a session with `LogcatConfig`.
3. Native forks `/system/bin/logcat` directly with sanitized arguments.
4. Native watches logcat output with `epoll`.
5. Native applies regex or literal filtering outside the Kotlin UI path.
6. Native writes accepted newline-delimited frames to a nonblocking pipe.
7. Kotlin reads and decodes UTF-8 bytes on `Dispatchers.IO`.
8. Kotlin emits raw and structured flows, and updates bounded history.
9. App stops the session when the diagnostic view or owner lifecycle ends.

## Delivery Contract

LogcatEngine is bounded and best-effort. It is not lossless.

The native pipe write path is nonblocking and all-or-drop. If Kotlin is not
keeping up, native can drop a line instead of blocking capture. The Kotlin
`SharedFlow` also uses bounded buffering with `DROP_OLDEST`, so slow collectors
see recent lines instead of forcing unbounded memory growth.

History is bounded by `LogcatConfig.historyLimit`. When the limit is reached,
older entries are removed.

This is intentional. A diagnostic log viewer should not destabilize the app it
is observing.

## Filtering

LogcatEngine has two filtering layers:

- logcat arguments: pid, tag filters, and minimum level
- native line filter: none, literal text, or regex

Literal text filtering is preferred for search boxes because it avoids regex
cost and escaping problems. Regex filtering is useful for expert diagnostics
where the caller intentionally wants a pattern.

Invalid native regex patterns are treated as no match filter updates rather
than fatal runtime errors.

## Sample App Boundary

The `:app` module is a sample playground. It is not the product surface and is
not part of the core library contract.

The sample should stay small and honest:

- show engine state
- start/stop/restart capture
- change filters and minimum level
- display structured logs
- clear/export history

It should not become a full log analysis product. Its job is to prove the core
API is usable.

## Operational Notes

- Prefer current-process capture for app-embedded diagnostic screens.
- Keep history limits realistic for the target device class.
- Avoid exporting logs without an app-owned privacy policy.
- Stop the session when the owning screen or service ends.
- Use no-op fallback behavior for release consumers where native loading might
  fail.
- Keep UI collectors lightweight; use structured logs from `session.logs`
  instead of reparsing raw strings in the UI.
