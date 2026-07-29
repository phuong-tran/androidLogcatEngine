package com.core.logcat.capture.core

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatEngineFactoryTest {
    @Test
    fun returnsNoOpEngineWhenNativeLibraryIsUnavailableOnHost() {
        val engine = LogcatEngineFactory.create()

        assertSame(NoOpLogcatEngine, engine)
    }

    @Test
    fun noOpEngineStartsWithErrorStateAndEmptyHistory() {
        val session = NoOpLogcatEngine.start(LogcatConfig())

        assertTrue(session.state.value is LogcatState.Error)
        assertTrue(session.history().isEmpty())
    }
}
