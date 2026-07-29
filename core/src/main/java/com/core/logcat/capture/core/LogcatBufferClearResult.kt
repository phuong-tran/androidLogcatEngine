package com.core.logcat.capture.core

/**
 * Result of requesting `logcat -c`.
 *
 * Android does not guarantee that every app can clear every log buffer. Treat
 * failures as a device/runtime policy outcome, not as a capture engine failure.
 */
sealed class LogcatBufferClearResult {
    /** The command completed with exit code 0. */
    object Success : LogcatBufferClearResult()

    /** The command failed, timed out, or could not be started. */
    data class Failed(
        val message: String,
        val exitCode: Int? = null,
        val output: String = "",
        val cause: Throwable? = null,
    ) : LogcatBufferClearResult()

    val isSuccess: Boolean
        get() = this is Success
}
