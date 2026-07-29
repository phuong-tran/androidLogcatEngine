package com.core.logcat.capture.core

/**
 * Parsed representation of one logcat line.
 *
 * Fields are nullable because logcat output format varies by Android version
 * and command flags. [raw] is always preserved for export or fallback display.
 */
data class LogLine(
    val raw: String,
    val timestamp: String? = null,
    val pid: Int? = null,
    val tid: Int? = null,
    val level: LogLevel? = null,
    val tag: String? = null,
    val message: String = raw,
)
