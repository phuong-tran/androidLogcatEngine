package com.core.logcat.capture.core

object NativeLogcatEngine : LogcatEngine {
    override val rawLogs = LogManager.logFlow
    override val logs = LogManager.structuredLogFlow
    override val state = LogManager.state

    val isAvailable: Boolean
        get() = LogManager.isNativeAvailable

    override fun start(config: LogcatConfig): LogcatSession = LogManager.start(config)

    override suspend fun startAndJoin(config: LogcatConfig): LogcatSession {
        return LogManager.startAndJoin(config)
    }

    override fun updateFilter(filter: LogFilter) {
        LogManager.updateFilter(filter)
    }

    override fun history(): List<LogLine> = LogManager.history()

    override fun clearHistory() {
        LogManager.clearHistory()
    }

    override suspend fun clearDeviceBuffers(): LogcatBufferClearResult {
        return LogManager.clearDeviceBuffers()
    }

    override fun setHistoryLimit(limit: Int) {
        LogManager.setHistoryLimit(limit)
    }

    override fun stop() {
        LogManager.stopNative()
    }

    override suspend fun stopAndJoin() {
        LogManager.stopNativeAndJoin()
    }
}
