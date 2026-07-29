package com.core.logcat.capture.core

object LogcatEngineFactory {
    /**
     * Creates the native-backed engine when available, otherwise returns a
     * no-op implementation that fails open and reports an error state.
     */
    fun create(): LogcatEngine {
        return if (NativeLogcatEngine.isAvailable) {
            NativeLogcatEngine
        } else {
            NoOpLogcatEngine
        }
    }
}
