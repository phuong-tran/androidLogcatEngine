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

/**
 * Optional bound service host for apps that want capture to outlive an Activity.
 */
class LogcatService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val binder = object : ILogControl.Stub() {

        override fun startLogging(tags: String?, regex: String?) {
            serviceScope.launch {
                val myPid = Process.myPid()
                LogManager.startAndJoin(
                    LogcatConfig(
                        pid = myPid,
                        tags = tags.orEmpty(),
                        minLevel = LogLevel.Verbose,
                        filter = regex.orEmpty()
                            .takeIf { it.isNotBlank() }
                            ?.let(LogFilter::Regex)
                            ?: LogFilter.None,
                    )
                )
            }
        }

        override fun updateFilters(tags: String?, regex: String?) {
            serviceScope.launch {
                LogManager.updateFilter(
                    regex.orEmpty()
                        .takeIf { it.isNotBlank() }
                        ?.let(LogFilter::Regex)
                        ?: LogFilter.None
                )
            }
        }

        override fun updateLiteral(text: String?) {
            serviceScope.launch {
                LogManager.updateFilter(
                    text.orEmpty()
                        .takeIf { it.isNotBlank() }
                        ?.let(LogFilter::Literal)
                        ?: LogFilter.None
                )
            }
        }

        override fun stopLogging() {
            serviceScope.launch {
                LogManager.stopNativeAndJoin()
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
