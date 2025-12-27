package com.core.logcat.capture.core

import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/**
 * LogManager: The bridge between Native LogEngine and Kotlin UI.
 * Handles thread-safe lifecycle management, backpressure control, and high-performance I/O.
 */
object LogManager {
    private const val TAG = "LogManager"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    private val globalLock = Mutex()

    /**
     * LOG CHANNEL WITH BACKPRESSURE POLICY
     * capacity = 5000: Buffers up to 5000 lines.
     * onBufferOverflow = DROP_OLDEST: Prevents OOM by discarding old logs when UI is slow,
     * ensuring the user always sees the most recent logs.
     */
    private val logChannel = Channel<String>(
        capacity = 5000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Public Flow to be observed by UI components.
     */
    val logFlow = logChannel.receiveAsFlow()

    init {
        System.loadLibrary("logcat_capture")
    }

    /**
     * Initializes and starts the native logcat capture process.
     */
    fun startNative(pid: String, tags: String, lv: String, reg: String) {
        scope.launch {
            stopWithLock()
            globalLock.withLock {
                val fd = configureAndStart(pid, tags, lv, reg)
                if (fd > 0) {
                    captureJob = launchCaptureJob(fd)
                }
            }
        }
    }

    /**
     * Safely stops the capture job and triggers native cleanup.
     */
    private suspend fun stopWithLock() {
        globalLock.withLock {
            captureJob?.let {
                if (it.isActive) it.cancelAndJoin()
            }
            captureJob = null
            withContext(Dispatchers.IO) { stop() }
        }
    }

    /**
     * Public API to stop logging from any thread.
     */
    fun stopNative() {
        scope.launch { stopWithLock() }
    }

    /**
     * Hot-swaps the regex filter pattern via Native Engine.
     */
    fun updateRegexFilter(regex: String) {
        scope.launch { globalLock.withLock { updateRegex(regex) } }
    }

    /**
     * Updates the filtering pattern using literal text.
     */
    fun updatePlainTextFilter(text: String) {
        scope.launch { globalLock.withLock { updateLiteral(text) } }
    }

    /**
     * DATA CAPTURE JOB
     * Reads raw bytes from the Native pipe and decodes them into UTF-8 lines.
     */
    private fun CoroutineScope.launchCaptureJob(fd: Int): Job = launch(Dispatchers.IO) {
        val byteBuffer = ByteBuffer.allocateDirect(256 * 1024)
        val charBuffer = CharBuffer.allocate(256 * 1024)
        val decoder = StandardCharsets.UTF_8.newDecoder()
        val lineBuilder = StringBuilder(4096)

        fun drainCharBuffer() {
            charBuffer.flip()
            while (charBuffer.hasRemaining()) {
                val c = charBuffer.get()
                if (c == '\n') {
                    if (lineBuilder.isNotEmpty()) {
                        logChannel.trySend(lineBuilder.toString())
                        lineBuilder.setLength(0)
                    }
                } else {
                    lineBuilder.append(c)
                }
            }
            charBuffer.clear()
        }

        try {
            ParcelFileDescriptor.adoptFd(fd).use { pfd ->
                val channel = FileInputStream(pfd.fileDescriptor).channel
                while (isActive && channel.isOpen) {
                    byteBuffer.clear()
                    val bytesRead = channel.read(byteBuffer)
                    if (bytesRead <= 0) break

                    byteBuffer.flip()
                    charBuffer.clear()

                    // Decode chunk -> chars
                    decoder.decode(byteBuffer, charBuffer, false)
                    drainCharBuffer()

                    // Shifts unread bytes to the beginning for the next read cycle
                    byteBuffer.compact()
                }

                // Flush decoder & gửi nốt phần còn lại (nếu có)
                byteBuffer.flip()
                decoder.decode(byteBuffer, charBuffer, true)
                drainCharBuffer()

                decoder.flush(charBuffer)
                drainCharBuffer()

                if (lineBuilder.isNotEmpty()) {
                    logChannel.trySend(lineBuilder.toString())
                    lineBuilder.setLength(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in log capture job", e)
        } finally {
            withContext(NonCancellable) {
                stop() // Final fallback cleanup
            }
        }
    }

    // --- NATIVE BRIDGES ---
    private external fun configureAndStart(p: String, t: String, l: String, r: String): Int
    private external fun stop()
    private external fun updateRegex(r: String)
    private external fun updateLiteral(t: String)
}