package com.core.logcat.capture.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Session facade for the current process-wide capture.
 *
 * A session does not own an independent native process; it delegates to the
 * singleton engine. It exists to give UI/sample code a compact handle for
 * streams, history, filters, export, and shutdown.
 */
class LogcatSession internal constructor(
    /** Raw lines emitted after native filtering. */
    val rawLogs: SharedFlow<String>,

    /** Parsed lines derived from [rawLogs]. */
    val logs: Flow<LogLine>,

    /** Engine lifecycle state. */
    val state: StateFlow<LogcatState>,
    private val historyProvider: () -> List<LogLine> = LogManager::history,
    private val clearHistoryAction: () -> Unit = LogManager::clearHistory,
    private val clearDeviceBuffersAction: suspend () -> LogcatBufferClearResult =
        LogManager::clearDeviceBuffers,
    private val setHistoryLimitAction: (Int) -> Unit = LogManager::setHistoryLimit,
    private val updateFilterAction: (LogFilter) -> Unit = LogManager::updateFilter,
    private val stopAction: () -> Unit = LogManager::stopNative,
    private val stopAndJoinAction: suspend () -> Unit = LogManager::stopNativeAndJoin,
) {
    /** Returns a bounded snapshot of retained parsed lines. */
    fun history(): List<LogLine> = historyProvider()

    /** Clears retained history while keeping active capture running. */
    fun clearHistory() {
        clearHistoryAction()
    }

    /** Requests `logcat -c` to clear device logcat buffers available to this app. */
    suspend fun clearDeviceBuffers(): LogcatBufferClearResult = clearDeviceBuffersAction()

    /** Changes the retained history limit for subsequent lines. */
    fun setHistoryLimit(limit: Int) {
        setHistoryLimitAction(limit)
    }

    /** Exports the current history snapshot to [file]. */
    fun exportHistory(file: File, format: LogExportFormat = LogExportFormat.Text) {
        LogExporter.write(history(), file, format)
    }

    /** Updates native line filtering without restarting logcat. */
    fun updateFilter(filter: LogFilter) {
        updateFilterAction(filter)
    }

    /** Queues asynchronous shutdown. */
    fun stop() {
        stopAction()
    }

    /** Stops capture and suspends until resources are released. */
    suspend fun stopAndJoin() {
        stopAndJoinAction()
    }
}
