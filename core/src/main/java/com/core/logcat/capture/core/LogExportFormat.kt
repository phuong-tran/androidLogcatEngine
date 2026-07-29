package com.core.logcat.capture.core

/** Supported history export formats. */
enum class LogExportFormat {
    /** One raw log line per output line. */
    Text,

    /** One JSON object per output line, suitable for streaming ingestion. */
    JsonLines,
}
