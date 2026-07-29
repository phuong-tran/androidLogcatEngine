package com.core.logcat.capture.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogLineParserTest {
    @Test
    fun parsesTimeFormat() {
        val raw = "07-29 13:45:12.345 D/MyTag   ( 1234): hello world"

        val line = LogLineParser.parse(raw)

        assertEquals(raw, line.raw)
        assertEquals("07-29 13:45:12.345", line.timestamp)
        assertEquals(1234, line.pid)
        assertNull(line.tid)
        assertEquals(LogLevel.Debug, line.level)
        assertEquals("MyTag", line.tag)
        assertEquals("hello world", line.message)
    }

    @Test
    fun parsesThreadTimeFormat() {
        val raw = "07-29 13:45:12.345 1234 5678 W MyTag: careful"

        val line = LogLineParser.parse(raw)

        assertEquals("07-29 13:45:12.345", line.timestamp)
        assertEquals(1234, line.pid)
        assertEquals(5678, line.tid)
        assertEquals(LogLevel.Warning, line.level)
        assertEquals("MyTag", line.tag)
        assertEquals("careful", line.message)
    }

    @Test
    fun keepsRawLineWhenFormatIsUnknown() {
        val raw = "not a standard logcat line"

        val line = LogLineParser.parse(raw)

        assertEquals(raw, line.raw)
        assertNull(line.timestamp)
        assertEquals(raw, line.message)
    }
}
