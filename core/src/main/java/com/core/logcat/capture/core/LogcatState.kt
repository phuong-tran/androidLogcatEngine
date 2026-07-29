package com.core.logcat.capture.core

/** Lifecycle state for the process-wide logcat capture engine. */
sealed class LogcatState {
    /** No capture has been requested in this process. */
    object Idle : LogcatState()

    /** A start request is configuring native resources. */
    object Starting : LogcatState()

    /** Native logcat capture is running with [config]. */
    data class Running(val config: LogcatConfig) : LogcatState()

    /** Capture was stopped by the caller. */
    object Stopped : LogcatState()

    /** Capture could not start or failed while reading. */
    data class Error(val message: String, val cause: Throwable? = null) : LogcatState()
}
