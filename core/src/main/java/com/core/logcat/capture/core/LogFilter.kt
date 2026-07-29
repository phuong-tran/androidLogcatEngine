package com.core.logcat.capture.core

/**
 * Line-level filter applied in native code after logcat emits a line.
 */
sealed class LogFilter {
    /** No extra line filtering. Only PID, tag, and level filters from logcat apply. */
    object None : LogFilter()

    /**
     * Case-insensitive ECMAScript regex.
     *
     * The pattern is compiled off the read hot path. Invalid patterns clear the
     * native filter so capture continues.
     */
    data class Regex(val pattern: String) : LogFilter()

    /** Case-insensitive substring match that avoids regex overhead. */
    data class Literal(val text: String) : LogFilter()

    internal val nativeValue: String
        get() = when (this) {
            None -> ""
            is Regex -> pattern
            is Literal -> text
        }

    internal val nativeMode: String
        get() = when (this) {
            None -> "none"
            is Regex -> "regex"
            is Literal -> "literal"
        }
}
