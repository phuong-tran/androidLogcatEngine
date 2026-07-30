package com.core.logcat.capture.core

/**
 * Configuration for one native logcat capture.
 *
 * [tags] uses logcat's complete tag filter syntax, for example `MyTag:V *:S`.
 * When [tags] is not blank, it controls logcat priorities and [minLevel] is not
 * appended. When [tags] is blank, [minLevel] is applied as `*:<level>`.
 * [filter] is an additional full-line filter applied by the native engine.
 */
data class LogcatConfig(
    val pid: Int? = null,
    val tags: String = "",
    val minLevel: LogLevel = LogLevel.Debug,
    val filter: LogFilter = LogFilter.None,
    val historyLimit: Int = 1_000,
) {
    companion object {
        /** Convenience config that captures only the current Android process. */
        fun currentProcess(
            tags: String = "",
            minLevel: LogLevel = LogLevel.Debug,
            filter: LogFilter = LogFilter.None,
            historyLimit: Int = 1_000,
        ): LogcatConfig = LogcatConfig(
            pid = android.os.Process.myPid(),
            tags = tags,
            minLevel = minLevel,
            filter = filter,
            historyLimit = historyLimit,
        )
    }
}
