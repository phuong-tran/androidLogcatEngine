package com.core.logcat.capture.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

object NoOpLogcatEngine : LogcatEngine {
    private val rawLogEvents = MutableSharedFlow<String>()
    private val stateEvents = MutableStateFlow<LogcatState>(
        LogcatState.Error("Native logcat library is unavailable")
    )

    override val rawLogs: SharedFlow<String> = rawLogEvents.asSharedFlow()
    override val logs: Flow<LogLine> = rawLogs.map(LogLineParser::parse)
    override val state: StateFlow<LogcatState> = stateEvents.asStateFlow()

    override fun start(config: LogcatConfig): LogcatSession {
        stateEvents.value = LogcatState.Error("Native logcat library is unavailable")
        return LogcatSession(
            rawLogs = rawLogs,
            logs = logs,
            state = state,
            historyProvider = { emptyList() },
            clearHistoryAction = {},
            clearDeviceBuffersAction = {
                LogcatBufferClearResult.Failed("Native logcat library is unavailable")
            },
            setHistoryLimitAction = {},
            updateFilterAction = {},
            stopAction = {},
            stopAndJoinAction = {},
        )
    }

    override suspend fun startAndJoin(config: LogcatConfig): LogcatSession = start(config)

    override fun updateFilter(filter: LogFilter) = Unit

    override fun history(): List<LogLine> = emptyList()

    override fun clearHistory() = Unit

    override suspend fun clearDeviceBuffers(): LogcatBufferClearResult {
        return LogcatBufferClearResult.Failed("Native logcat library is unavailable")
    }

    override fun setHistoryLimit(limit: Int) = Unit

    override fun stop() = Unit

    override suspend fun stopAndJoin() = Unit
}
