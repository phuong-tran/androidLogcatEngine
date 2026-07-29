package com.core.logcat.capture.core

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * Singleton bridge between the native capture engine and Kotlin consumers.
 *
 * The native layer currently supports one active logcat process per app process.
 * Lifecycle commands are serialized in call order so a later stop cannot be
 * overtaken by an earlier asynchronous start.
 */
object LogManager {
    private const val TAG = "LogManager"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private val globalLock = Mutex()
    private val lifecycleQueueLock = Any()
    private var lifecycleTail: Job = SupervisorJob().also { it.complete() }
    private val historyLock = Any()
    private val history = ArrayDeque<LogLine>()

    @Volatile
    private var historyLimit = DEFAULT_HISTORY_LIMIT

    /**
     * Hot stream of raw lines. Slow collectors may miss old entries; the bounded
     * replay-free buffer keeps capture memory bounded under bursty log output.
     */
    private val logEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 5000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Raw log lines emitted after native filtering.
     *
     * This is a hot stream with no replay. Use [history] when a point-in-time
     * snapshot is needed.
     */
    val logFlow: SharedFlow<String> = logEvents.asSharedFlow()

    /** Parsed view over [logFlow]. Parsing happens in the collector context. */
    val structuredLogFlow: Flow<LogLine> = logFlow.map(LogLineParser::parse)

    private val _state = MutableStateFlow<LogcatState>(LogcatState.Idle)

    /** Current lifecycle state for the single active native capture. */
    val state: StateFlow<LogcatState> = _state.asStateFlow()

    internal val isNativeAvailable: Boolean
        get() = isNativeLibraryLoaded

    private val nativeLibraryLoaded = lazy {
        runCatching {
            System.loadLibrary("logcat_capture")
        }.onFailure { throwable ->
            _state.value = LogcatState.Error("Native logcat library failed to load", throwable)
        }.isSuccess
    }

    private val isNativeLibraryLoaded: Boolean
        get() = nativeLibraryLoaded.value

    /**
     * Legacy fire-and-forget entry point retained for existing callers.
     */
    fun startNative(pid: String, tags: String, lv: String, reg: String) {
        val legacyConfig = LogcatConfig(
            pid = pid.toIntOrNull(),
            tags = tags,
            minLevel = LogLevel.fromSymbol(lv.firstOrNull() ?: 'D') ?: LogLevel.Debug,
            filter = reg.takeIf { it.isNotBlank() }?.let(LogFilter::Regex) ?: LogFilter.None,
        )
        start(legacyConfig)
    }

    /**
     * Queues a start request and returns a session facade immediately.
     *
     * Observe [state] to know when the native process reaches [LogcatState.Running],
     * or use [startAndJoin] when the caller is already in a coroutine and needs
     * startup completion before continuing.
     */
    fun start(config: LogcatConfig = LogcatConfig.currentProcess()): LogcatSession {
        val session = newSession()
        enqueueLifecycle {
            startNativeAndJoin(config)
        }
        return session
    }

    /** Starts capture and waits until native startup has completed or failed. */
    suspend fun startAndJoin(config: LogcatConfig = LogcatConfig.currentProcess()): LogcatSession {
        val session = newSession()
        enqueueLifecycle {
            startNativeAndJoin(config)
        }.join()
        return session
    }

    private suspend fun startNativeAndJoin(config: LogcatConfig) {
        if (!isNativeAvailable) {
            _state.value = LogcatState.Error("Native logcat library is unavailable")
            return
        }

        globalLock.withLock {
            setHistoryLimit(config.historyLimit)
            stopLocked(publishStopped = false)
            _state.value = LogcatState.Starting
            val fd = configureAndStart(
                config.pid?.toString().orEmpty(),
                config.tags,
                config.minLevel.nativeValue,
                config.filter.nativeValue,
                config.filter.nativeMode,
            )
            if (fd >= 0) {
                captureJob = scope.launchCaptureJob(fd)
                _state.value = LogcatState.Running(config)
            } else {
                _state.value = LogcatState.Error("Native logcat capture failed to start")
            }
        }
    }

    private suspend fun stopLocked(publishStopped: Boolean = true) {
        val job = captureJob
        captureJob = null

        // Native stop closes the producer side first; that gives the reader job
        // a happens-before edge for pipe EOF before we join/cancel it.
        if (nativeLibraryLoaded.isInitialized() && nativeLibraryLoaded.value) {
            withContext(Dispatchers.IO) { stop() }
        }

        job?.let {
            if (it.isActive) it.cancelAndJoin()
        }
        if (publishStopped) {
            _state.value = LogcatState.Stopped
        }
    }

    /**
     * Queues a stop request. Use [stopNativeAndJoin] when shutdown completion
     * matters, for example before disposing an owning component.
     */
    fun stopNative() {
        enqueueLifecycle {
            globalLock.withLock { stopLocked() }
        }
    }

    /** Stops capture and waits until native and Kotlin resources are released. */
    suspend fun stopNativeAndJoin() {
        enqueueLifecycle {
            globalLock.withLock { stopLocked() }
        }.join()
    }

    /**
     * Queues a regex filter update. Invalid regex patterns fail open in native
     * code by clearing the filter.
     */
    fun updateRegexFilter(regex: String) {
        if (!isNativeAvailable) return
        enqueueLifecycle {
            globalLock.withLock { updateRegex(regex) }
        }
    }

    /**
     * Queues a case-insensitive literal filter update.
     */
    fun updatePlainTextFilter(text: String) {
        if (!isNativeAvailable) return
        enqueueLifecycle {
            globalLock.withLock { updateLiteral(text) }
        }
    }

    /** Updates the native line filter without restarting logcat. */
    fun updateFilter(filter: LogFilter) {
        when (filter) {
            LogFilter.None -> updateRegexFilter("")
            is LogFilter.Regex -> updateRegexFilter(filter.pattern)
            is LogFilter.Literal -> updatePlainTextFilter(filter.text)
        }
    }

    /** Returns a stable copy of the bounded parsed history buffer. */
    fun history(): List<LogLine> = synchronized(historyLock) {
        history.toList()
    }

    /** Clears retained history without stopping active capture. */
    fun clearHistory() {
        synchronized(historyLock) {
            history.clear()
        }
    }

    /**
     * Requests Android's logcat buffers to be cleared with `logcat -c`.
     *
     * This is process-external state and may be denied or partially applied by
     * the device. It is intentionally separate from [clearHistory].
     */
    suspend fun clearDeviceBuffers(): LogcatBufferClearResult = withContext(Dispatchers.IO) {
        runCatching<LogcatBufferClearResult> {
            val process = ProcessBuilder("/system/bin/logcat", "-c")
                .redirectErrorStream(true)
                .start()
            val deadlineNanos = System.nanoTime() + LOGCAT_CLEAR_TIMEOUT_MS * 1_000_000
            var result: LogcatBufferClearResult

            while (true) {
                val exitCode = runCatching { process.exitValue() }.getOrNull()
                if (exitCode != null) {
                    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    result = if (exitCode == 0) {
                        LogcatBufferClearResult.Success
                    } else {
                        LogcatBufferClearResult.Failed(
                            message = "logcat -c exited with code $exitCode",
                            exitCode = exitCode,
                            output = output,
                        )
                    }
                    break
                }

                if (System.nanoTime() >= deadlineNanos) {
                    process.destroy()
                    result = LogcatBufferClearResult.Failed(
                        message = "logcat -c timed out",
                    )
                    break
                }

                Thread.sleep(LOGCAT_CLEAR_POLL_MS)
            }
            result
        }.getOrElse { throwable ->
            LogcatBufferClearResult.Failed(
                message = "Unable to run logcat -c",
                cause = throwable,
            )
        }
    }

    /** Sets the retained history size. Values below zero are treated as zero. */
    fun setHistoryLimit(limit: Int) {
        historyLimit = limit.coerceAtLeast(0)
        synchronized(historyLock) {
            trimHistoryLocked()
        }
    }

    private fun newSession(): LogcatSession = LogcatSession(
        rawLogs = logFlow,
        logs = structuredLogFlow,
        state = state,
    )

    private fun enqueueLifecycle(block: suspend () -> Unit): Job {
        return synchronized(lifecycleQueueLock) {
            val previous = lifecycleTail
            val next = scope.launch {
                previous.join()
                block()
            }
            lifecycleTail = next
            next
        }
    }

    /**
     * Reader job for the native pipe.
     *
     * It owns the adopted file descriptor and decodes UTF-8 incrementally so
     * split multibyte sequences remain valid across native write boundaries.
     */
    private fun CoroutineScope.launchCaptureJob(fd: Int): Job = launch(Dispatchers.IO) {
        val byteBuffer = ByteBuffer.allocateDirect(256 * 1024)
        val charBuffer = CharBuffer.allocate(256 * 1024)
        val decoder = StandardCharsets.UTF_8.newDecoder()
        val lineBuilder = StringBuilder(4096)

        fun drainCharBuffer() {
            charBuffer.flip()
            while (charBuffer.hasRemaining()) {
                val c = charBuffer.get()
                if (c == '\n') {
                    if (lineBuilder.isNotEmpty()) {
                        emitLogLine(lineBuilder.toString())
                        lineBuilder.setLength(0)
                    }
                } else {
                    lineBuilder.append(c)
                }
            }
            charBuffer.clear()
        }

        fun decodeBufferedBytes(endOfInput: Boolean) {
            byteBuffer.flip()
            while (true) {
                val result = decoder.decode(byteBuffer, charBuffer, endOfInput)
                drainCharBuffer()

                when {
                    result.isOverflow -> continue
                    result.isUnderflow -> break
                    result.isError -> result.throwException()
                }
            }
            byteBuffer.compact()
        }

        fun flushDecoder() {
            byteBuffer.flip()
            while (true) {
                val result = decoder.decode(byteBuffer, charBuffer, true)
                drainCharBuffer()

                when {
                    result.isOverflow -> continue
                    result.isUnderflow -> break
                    result.isError -> result.throwException()
                }
            }
            byteBuffer.clear()

            while (true) {
                val result = decoder.flush(charBuffer)
                drainCharBuffer()

                when {
                    result.isOverflow -> continue
                    result.isUnderflow -> break
                    result.isError -> result.throwException()
                }
            }
        }

        try {
            ParcelFileDescriptor.adoptFd(fd).use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                    while (isActive && channel.isOpen) {
                        val bytesRead = channel.read(byteBuffer)
                        if (bytesRead < 0) break
                        if (bytesRead == 0) {
                            yield()
                            continue
                        }

                        decodeBufferedBytes(endOfInput = false)
                    }
                }

                flushDecoder()

                if (lineBuilder.isNotEmpty()) {
                    emitLogLine(lineBuilder.toString())
                    lineBuilder.setLength(0)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = LogcatState.Error("Error in log capture job", e)
            Log.e(TAG, "Error in log capture job", e)
        } finally {
            withContext(NonCancellable) {
                if (isNativeAvailable) {
                    stop() // Final fallback cleanup
                }
            }
        }
    }

    private fun emitLogLine(raw: String) {
        val parsed = LogLineParser.parse(raw)
        synchronized(historyLock) {
            if (historyLimit > 0) {
                history.addLast(parsed)
                trimHistoryLocked()
            }
        }
        logEvents.tryEmit(raw)
    }

    private fun trimHistoryLocked() {
        while (history.size > historyLimit) {
            history.removeFirst()
        }
    }

    // --- NATIVE BRIDGES ---
    private const val FILTER_MODE_REGEX = "regex"
    private const val DEFAULT_HISTORY_LIMIT = 1_000
    private const val LOGCAT_CLEAR_TIMEOUT_MS = 2_000L
    private const val LOGCAT_CLEAR_POLL_MS = 25L

    private external fun configureAndStart(
        p: String,
        t: String,
        l: String,
        f: String,
        m: String,
    ): Int

    private external fun stop()
    private external fun updateRegex(r: String)
    private external fun updateLiteral(t: String)
}
