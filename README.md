# LogcatEngine - High-Performance Logcat Logger for Android

LogcatEngine is an Android library designed to efficiently capture and display your application's logcat output. The library utilizes **C++17** at the native layer to monitor logcat via **Linux Epoll** and delivers it to the Kotlin layer via a **Unix Pipe**, ensuring extreme performance and zero impact on the UI thread.



## Key Features

- **Extreme Performance**: Written in C++17 using event-driven `epoll` and `std::string_view` for zero-copy parsing.
- **Modern Kotlin API**: Provides a `Flow` to emit log lines, integrating seamlessly with Coroutines and Jetpack Compose.
- **Watchdog & Resilience**: Automatically monitors and restarts the logcat process if it's terminated by the system.
- **Backpressure Handling**: Uses a `Channel` with a `DROP_OLDEST` policy to prevent memory overflow during log storms.
- **Memory Optimized**: Employs NIO's `DirectByteBuffer` and Native Buffers to bypass Java GC pressure.
- **Hot-Swappable Filters**: Update regex or plain text filters in real-time without restarting the capture thread.

## How It Works

1.  **`LogManager.kt`**: The main public API entry point.
2.  **JNI Layer**: Bridges Kotlin calls to the C++ core.
3.  **Native Engine (C++17)**:
    * Forks a dedicated `logcat` process using `fork()` and `execl()`.
    * Uses `epoll` to wait for data on the pipe with zero CPU wakeups when idle.
    * Processes streams at the byte level with `memchr` (SIMD-ready) for ultra-fast line splitting.
4.  **Data Pipeline**:
    * The file descriptor (FD) is returned to Kotlin.
    * `LogManager` reads from the pipe using an **NIO Channel** on `Dispatchers.IO`.
    * Binary data is decoded and emitted via `SharedFlow`.



## How to Use

### 1. Initialize the Engine
In your `Activity` or `Service`, initialize the engine. To capture logs only from your app, pass the current PID.

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val myPid = android.os.Process.myPid().toString()
    // Start engine: PID, TagFilter, LogLevel, Regex
    LogManager.startNative(pid = myPid, tags = "", lv = "D", reg = "")
}

### 2. Collect and Display Logs (Example with Jetpack Compose)

Use `collectAsState` to listen to the `logFlow` from `LogManager` and update the UI.

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.core.logcat.capture.core.LogManager

@Composable
fun LogConsole() {
    // Collect logs from the Flow
    val latestLog by LogManager.logFlow.collectAsState(initial = "...")

    // UI to display the logs (e.g., in a LazyColumn)
    // ...
}
```

### 3. Clean Up Resources

It's crucial to stop the engine when it's no longer needed to release the native thread and close the pipe. Call `stopNative()` in `onDestroy`.

```kotlin
class MainActivity : ComponentActivity() {
    // ...

    override fun onDestroy() {
        // Stop the engine and release resources
        LogManager.stopNative()
        super.onDestroy()
    }
}
```

## Full Example with Jetpack Compose

The `MainActivity.kt` file in the `app` module provides a complete example of how to integrate `LogcatEngine` into a UI built with Compose. It includes:
- Engine initialization and cleanup.
- Simulation of continuous logging.
- Displaying logs in a high-performance `LazyColumn`.
- Auto-scrolling to the latest log.
- Color-coding log lines based on their level (Debug, Error, Warning).

You can run the `app` module to see the library in action.
