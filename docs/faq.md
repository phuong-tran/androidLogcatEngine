# LogcatEngine FAQ

## Is LogcatEngine A Logging SDK?

No. It captures logcat output that already exists. It does not replace the
app's logging API, analytics system, crash reporting, or backend telemetry.

Think of it as an app-embedded diagnostic reader.

## Why Use It If Android Studio Already Has Logcat?

Android Studio and `adb logcat` are developer tools. They are not always
available to QA, support, internal dogfood users, or devices in the field.

LogcatEngine lets the app expose recent diagnostic context through an internal
screen or sample UI.

## Is It Lossless?

No.

LogcatEngine is bounded and best-effort. Native writes to Kotlin through a
nonblocking pipe, and Kotlin exposes a bounded `SharedFlow`. When pressure is
high or consumers are slow, old or new lines may be dropped so capture cannot
block the app.

## Why Does It Drop Lines?

Because diagnostic capture should not make the host app slow, unstable, or
memory hungry.

Dropping logs is not ideal, but it is better than letting log viewing create an
unbounded queue or block a native capture thread during a log storm.

## Can It Capture Crashes?

It can capture logcat lines that appear before the process dies, but it is not a
crash reporter. A crash reporter owns process death handling, persistence,
symbolication, grouping, and upload policy. LogcatEngine does not.

For crash workflows, treat LogcatEngine as recent supporting context, not as
the source of truth.

## Should This Ship In Production?

It can ship when hidden behind an app-owned diagnostic feature flag, internal
build type, QA mode, or support-only entry point.

Apps should be careful with privacy. Logcat may contain sensitive data emitted
by the app or libraries. Export and sharing must be governed by the host app.

## Why Is There A No-Op Engine?

The no-op engine keeps consumers fail-open. If native loading fails because of
packaging, ABI, or device constraints, `LogcatEngineFactory.create()` can return
an implementation that reports an error state instead of crashing the app.

## Should I Use Text Or Regex Filtering?

Use literal text for normal search. It is faster and safer.

Use regex only when the user or diagnostic workflow needs pattern matching.
Regex patterns can be expensive or invalid, so they should be treated as an
expert tool.

## Can It Clear Logcat Buffers?

It can request `logcat -c` through `clearDeviceBuffers()`, but Android treats
that as device/runtime state. The command is not scoped to the app's PID, and
some devices may deny or partially apply it.

Use it behind explicit user confirmation. For normal UI cleanup, prefer
`clearHistory()`, which only clears LogcatEngine's in-memory history.

## What Is The Value Of The Sample App?

The sample app proves the library API works:

- current-process capture
- state observation
- structured log rendering
- filter changes
- bounded history
- JSON Lines export

It is intentionally not the main product.

## How Do I Reduce Drops?

- Keep UI collectors lightweight.
- Prefer literal filters when possible.
- Capture only the current process for app diagnostics.
- Use a realistic `historyLimit`.
- Avoid rendering huge lists without lazy UI.
- Export bounded history rather than keeping everything in memory.
