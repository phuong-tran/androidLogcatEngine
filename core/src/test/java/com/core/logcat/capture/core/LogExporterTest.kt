package com.core.logcat.capture.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LogExporterTest {
    @Test
    fun escapesJsonLineFields() {
        val line = LogLine(
            raw = "D/Tag: quote \" and slash \\",
            timestamp = "07-29 13:45:12.345",
            pid = 1234,
            tid = 5678,
            level = LogLevel.Debug,
            tag = "Tag",
            message = "quote \" and slash \\",
        )

        val file = File.createTempFile("log-exporter", ".jsonl")
        try {
            LogExporter.write(listOf(line), file, LogExportFormat.JsonLines)

            assertEquals(
                """{"raw":"D/Tag: quote \" and slash \\","timestamp":"07-29 13:45:12.345","pid":1234,"tid":5678,"level":"Debug","tag":"Tag","message":"quote \" and slash \\"}""",
                file.readText(),
            )
        } finally {
            file.delete()
        }
    }
}
