package com.core.logcat.capture.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.core.logcat.capture.ILogControl
import com.core.logcat.capture.core.LogFilter
import com.core.logcat.capture.core.LogLevel
import com.core.logcat.capture.core.LogManager
import com.core.logcat.capture.core.LogcatConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Optional bound service host for apps that want capture to outlive an Activity.
 */
class LogcatService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val commandLock = Mutex()
    private var activeTags: String = ""

    private fun regexFilter(regex: String?): LogFilter =
        regex.orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let(LogFilter::Regex)
            ?: LogFilter.None

    private suspend fun restartLogging(tags: String, filter: LogFilter) {
        LogManager.startAndJoin(
            LogcatConfig(
                pid = Process.myPid(),
                tags = tags,
                minLevel = LogLevel.Verbose,
                filter = filter,
            )
        )
        activeTags = tags
    }

    private val binder = object : ILogControl.Stub() {

        override fun startLogging(tags: String?, regex: String?) {
            serviceScope.launch {
                commandLock.withLock {
                    restartLogging(tags.orEmpty(), regexFilter(regex))
                }
            }
        }

        override fun updateFilters(tags: String?, regex: String?) {
            serviceScope.launch {
                commandLock.withLock {
                    val nextTags = tags.orEmpty()
                    val nextFilter = regexFilter(regex)
                    if (nextTags == activeTags) {
                        LogManager.updateFilter(nextFilter)
                    } else {
                        restartLogging(nextTags, nextFilter)
                    }
                }
            }
        }

        override fun updateLiteral(text: String?) {
            serviceScope.launch {
                commandLock.withLock {
                    LogManager.updateFilter(
                        text.orEmpty()
                            .takeIf { it.isNotBlank() }
                            ?.let(LogFilter::Literal)
                            ?: LogFilter.None
                    )
                }
            }
        }

        override fun stopLogging() {
            serviceScope.launch {
                commandLock.withLock {
                    LogManager.stopNativeAndJoin()
                    activeTags = ""
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        LogManager.stopNative()
        serviceScope.cancel()
        super.onDestroy()
    }
}
