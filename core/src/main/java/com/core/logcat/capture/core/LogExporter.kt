package com.core.logcat.capture.core

import java.io.File
import java.nio.charset.StandardCharsets

/** Writes retained log history to disk. */
object LogExporter {
    /** Creates parent directories as needed and writes [lines] using UTF-8. */
    fun write(lines: List<LogLine>, file: File, format: LogExportFormat = LogExportFormat.Text) {
        file.parentFile?.mkdirs()
        when (format) {
            LogExportFormat.Text -> file.writeText(
                lines.joinToString(separator = "\n") { it.raw },
                StandardCharsets.UTF_8,
            )
            LogExportFormat.JsonLines -> file.writeText(
                lines.joinToString(separator = "\n") { it.toJsonLine() },
                StandardCharsets.UTF_8,
            )
        }
    }

    internal fun LogLine.toJsonLine(): String = buildString {
        append('{')
        appendJsonField("raw", raw)
        append(',')
        appendJsonField("timestamp", timestamp)
        append(',')
        appendJsonField("pid", pid)
        append(',')
        appendJsonField("tid", tid)
        append(',')
        appendJsonField("level", level?.name)
        append(',')
        appendJsonField("tag", tag)
        append(',')
        appendJsonField("message", message)
        append('}')
    }

    private fun StringBuilder.appendJsonField(name: String, value: String?) {
        append('"').append(name).append('"').append(':')
        if (value == null) {
            append("null")
        } else {
            append('"').append(value.escapeJson()).append('"')
        }
    }

    private fun StringBuilder.appendJsonField(name: String, value: Int?) {
        append('"').append(name).append('"').append(':')
        append(value?.toString() ?: "null")
    }

    private fun String.escapeJson(): String = buildString {
        for (char in this@escapeJson) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char < ' ') {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
}
