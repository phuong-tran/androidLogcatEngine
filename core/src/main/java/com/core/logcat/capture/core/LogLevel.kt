package com.core.logcat.capture.core

/** Android log priority used by logcat tag filters. */
enum class LogLevel(
    val nativeValue: String,
    private vararg val symbols: Char,
) {
    Verbose("V", 'V'),
    Debug("D", 'D'),
    Info("I", 'I'),
    Warning("W", 'W'),
    Error("E", 'E'),
    Fatal("F", 'F', 'A'),
    Silent("S", 'S');

    companion object {
        /** Maps a logcat priority symbol such as `D` or `W` to [LogLevel]. */
        fun fromSymbol(symbol: Char): LogLevel? {
            val upper = symbol.uppercaseChar()
            return entries.firstOrNull { level ->
                level.symbols.any { it == upper }
            }
        }
    }
}
