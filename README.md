# LogcatEngine - High-Performance Logcat Logger for Android

[![CI](https://github.com/phuong-tran/androidLogcatEngine/actions/workflows/ci.yml/badge.svg)](https://github.com/phuong-tran/androidLogcatEngine/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-1.2-blue.svg)](#maven-publishing)

LogcatEngine is an Android library designed to efficiently capture and display your application's logcat output. The library utilizes **C++17** at the native layer to monitor logcat via **Linux Epoll** and delivers it to the Kotlin layer via a **Unix Pipe**, keeping log capture work off the UI thread.



## Key Features

- **High-Throughput Capture**: Written in C++17 using event-driven `epoll`, bounded pipes, and low-allocation line scanning.
- **Modern Kotlin API**: Provides raw and structured `Flow` streams, integrating seamlessly with Coroutines and Jetpack Compose.
- **Watchdog & Resilience**: Automatically monitors and restarts the logcat process if it's terminated by the system.
- **Backpressure Handling**: Uses a bounded `SharedFlow` buffer with a `DROP_OLDEST` policy to prevent memory overflow during log storms.
- **Memory Optimized**: Employs NIO's `DirectByteBuffer` and Native Buffers to bypass Java GC pressure.
- **Hot-Swappable Filters**: Update regex or plain text filters in real-time without restarting the capture thread.
- **Fail-Open Factory**: Provides a no-op engine when the native library is unavailable.

## Runtime Contract

LogcatEngine is a bounded, best-effort capture library. It is designed to keep
applications responsive during log storms, not to provide a lossless audit log.

The native writer uses non-blocking all-or-drop pipe writes, and the Kotlin
stream uses a bounded `SharedFlow` buffer with `DROP_OLDEST`. If consumers are
slow, older lines may be dropped so log capture does not block the engine or
grow memory without limit. The in-memory history is also bounded by
`LogcatConfig.historyLimit`.

Callers should treat the stream as recent diagnostic context. App behavior
should not depend on every logcat line being delivered.

## How It Works

1.  **`LogcatEngine`**: The public facade returned by `LogcatEngineFactory`.
    `LogManager` remains available as the legacy singleton.
2.  **JNI Layer**: Bridges Kotlin calls to the C++ core.
3.  **Native Engine (C++17)**:
    * Forks a dedicated `logcat` process using `fork()` and `execv()`.
    * Uses `epoll` to wait for data on the pipe without polling when idle.
    * Processes streams at the byte level and scans newline boundaries with low-allocation buffers.
4.  **Data Pipeline**:
    * The file descriptor (FD) is returned to Kotlin.
    * `LogManager` reads from the pipe using an **NIO Channel** on `Dispatchers.IO`.
    * Binary data is decoded and emitted via raw and structured `Flow` streams.



## How to Use

### 1. Initialize the Engine
In your `Activity` or `Service`, initialize the engine with a typed config. To capture logs only from your app, pass the current PID.

```kotlin
class MainActivity : ComponentActivity() {
    private val engine = LogcatEngineFactory.create()
    private lateinit var session: LogcatSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = engine.start(
            LogcatConfig.currentProcess(
                minLevel = LogLevel.Debug,
                filter = LogFilter.None,
            )
        )
    }
}
```

### 2. Collect and Display Logs (Example with Jetpack Compose)

Use `collectAsState` to listen to the session streams and update the UI.

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.core.logcat.capture.core.LogFilter
import com.core.logcat.capture.core.LogLevel
import com.core.logcat.capture.core.LogcatEngineFactory
import com.core.logcat.capture.core.LogcatConfig
import com.core.logcat.capture.core.LogcatSession

@Composable
fun LogConsole(session: LogcatSession) {
    // Collect raw logs from the Flow
    val latestLog by session.rawLogs.collectAsState(initial = "...")

    // Or collect parsed logs from session.logs
    // Engine status is available from session.state
    // Recent parsed logs are available from session.history()
    // UI to display the logs (e.g., in a LazyColumn)
    // ...
}
```

### 3. Clean Up Resources

It's crucial to stop the engine when it's no longer needed to release the native thread and close the pipe. Call `session.stop()` in `onDestroy`.

```kotlin
class MainActivity : ComponentActivity() {
    // ...

    override fun onDestroy() {
        // Stop the engine and release resources
        session.stop()
        super.onDestroy()
    }
}
```

For coroutine-based cleanup, call `session.stopAndJoin()` from a coroutine.

You can also export the bounded in-memory history:

```kotlin
session.exportHistory(file, LogExportFormat.JsonLines)
```

## Maven Publishing

The `:core` module is configured as a publishable Android AAR:

```bash
./gradlew :core:publishReleasePublicationToLocalStaticMavenRepository
```

The artifact coordinates are currently:

```kotlin
implementation("io.github.phuongtran:logcat-engine-core:1.2")
```

This publishing setup is intentionally local/static for now. Release signing,
license policy, and public repository hosting should be finalized before a real
external release.

## Documentation

Read [docs/doc.md](docs/doc.md) for scope, runtime model, and delivery
contract. Common product and integration questions are in
[docs/faq.md](docs/faq.md). Build, publish, and diagnostic commands are in
[docs/testing.md](docs/testing.md).

## Full Example with Jetpack Compose

The `MainActivity.kt` file in the `app` module provides a complete example of how to integrate `LogcatEngine` into a UI built with Compose. It includes:
- Engine initialization and cleanup.
- Opt-in warning-log simulation.
- Displaying logs in a high-performance `LazyColumn`.
- Whole-screen scrolling with a back-to-top control.
- Color-coding log lines based on their level (Debug, Error, Warning).

You can run the `app` module to see the library in action.
