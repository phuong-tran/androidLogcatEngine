package com.core.logcat.capture.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public runtime facade for logcat capture.
 *
 * Implementations may be backed by native code or may fail open when the
 * native library is unavailable. Callers should observe [state] instead of
 * depending on startup to throw.
 */
interface LogcatEngine {
    /** Raw log lines emitted after native filtering. This stream has no replay. */
    val rawLogs: SharedFlow<String>

    /** Structured log lines parsed from [rawLogs]. */
    val logs: Flow<LogLine>

    /** Lifecycle state for the single active capture owned by this engine. */
    val state: StateFlow<LogcatState>

    /**
     * Queues a native capture start and returns a session facade immediately.
     *
     * A later lifecycle call on the same engine is serialized after this start
     * request. Observe [state] or use [startAndJoin] when startup completion is
     * required before continuing.
     */
    fun start(config: LogcatConfig = LogcatConfig.currentProcess()): LogcatSession

    /** Starts capture and suspends until native startup has completed or failed. */
    suspend fun startAndJoin(config: LogcatConfig = LogcatConfig.currentProcess()): LogcatSession

    /** Updates line filtering in native code without restarting logcat. */
    fun updateFilter(filter: LogFilter)

    /** Returns a bounded snapshot of parsed lines retained by the engine. */
    fun history(): List<LogLine>

    /** Clears retained history without affecting active collectors. */
    fun clearHistory()

    /** Requests `logcat -c` to clear device logcat buffers available to this app. */
    suspend fun clearDeviceBuffers(): LogcatBufferClearResult

    /** Sets the maximum number of parsed lines retained by [history]. */
    fun setHistoryLimit(limit: Int)

    /** Queues an asynchronous stop request. */
    fun stop()

    /** Stops capture and waits until native and Kotlin resources are released. */
    suspend fun stopAndJoin()
}
