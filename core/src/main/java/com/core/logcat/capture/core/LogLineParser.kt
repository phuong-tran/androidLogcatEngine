package com.core.logcat.capture.core

/**
 * Best-effort parser for common logcat text formats emitted by the native engine.
 */
object LogLineParser {
    private val threadTimePattern = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFAS])\s+([^:]+):\s?(.*)$"""
    )
    private val timePattern = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEFAS])\/(.+?)\s*\(\s*(\d+)\):\s?(.*)$"""
    )

    /** Returns structured fields when the line matches a known format. */
    fun parse(raw: String): LogLine {
        parseThreadTime(raw)?.let { return it }
        parseTime(raw)?.let { return it }
        return LogLine(raw = raw)
    }

    private fun parseThreadTime(raw: String): LogLine? {
        val match = threadTimePattern.matchEntire(raw) ?: return null
        val values = match.groupValues
        return LogLine(
            raw = raw,
            timestamp = values[1],
            pid = values[2].toIntOrNull(),
            tid = values[3].toIntOrNull(),
            level = LogLevel.fromSymbol(values[4].first()),
            tag = values[5].trim(),
            message = values[6],
        )
    }

    private fun parseTime(raw: String): LogLine? {
        val match = timePattern.matchEntire(raw) ?: return null
        val values = match.groupValues
        return LogLine(
            raw = raw,
            timestamp = values[1],
            pid = values[4].toIntOrNull(),
            level = LogLevel.fromSymbol(values[2].first()),
            tag = values[3].trim(),
            message = values[5],
        )
    }
}
