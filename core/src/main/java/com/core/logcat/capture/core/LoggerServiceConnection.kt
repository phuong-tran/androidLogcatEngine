package com.core.logcat.capture.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.core.logcat.capture.ILogControl
import com.core.logcat.capture.service.LogcatService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Convenience client for the optional bound [LogcatService].
 *
 * This API is intentionally thin: it forwards lifecycle and filter commands
 * over AIDL while exposing connection state as a flow.
 */
@Suppress("unused")
object LoggerServiceConnection : ServiceConnection {
    private var logControl: ILogControl? = null

    @Volatile
    private var isBound = false

    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isConnected = MutableStateFlow(false)

    /** True while the AIDL service connection is alive. */
    val isConnected = _isConnected.asStateFlow()

    /** Requests service-hosted capture. */
    fun startLogging(tags: String, regex: String) {
        connectionScope.launch {
            if (_isConnected.value) {
                try {
                    logControl?.startLogging(tags, regex)
                } catch (e: Exception) {
                    // Fail-safe for DeadObjectException or IPC errors
                }
            }
        }
    }

    /**
     * Requests filter updates in the service.
     *
     * Regex changes are hot-swapped when [tags] is unchanged. Tag changes restart
     * capture because logcat tag filters are command-line arguments.
     */
    fun updateFilters(tags: String, regex: String): Boolean {
        if (!_isConnected.value) return false
        connectionScope.launch {
            try {
                logControl?.updateFilters(tags, regex)
            } catch (e: Exception) { }
        }
        return true
    }

    /** Requests a literal filter update in the service. */
    fun updateLiteralSearch(text: String): Boolean {
        if (!_isConnected.value) return false
        connectionScope.launch {
            try {
                logControl?.updateLiteral(text)
            } catch (e: Exception) { }
        }
        return true
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        logControl = ILogControl.Stub.asInterface(binder)
        _isConnected.value = true
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        logControl = null
        _isConnected.value = false
    }

    /** Binds to [LogcatService] using the application context. */
    fun bind(context: Context) {
        if (isBound) return

        val appContext = context.applicationContext
        val intent = Intent(appContext, LogcatService::class.java)
        isBound = try {
            appContext.bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            false
        }
    }

    /** Stops remote capture, then unbinds on the main thread. */
    fun unbind(context: Context) {
        connectionScope.launch {
            if (_isConnected.value) {
                try {
                    logControl?.stopLogging()
                    delay(100)
                } catch (e: Exception) { }
            }

            withContext(Dispatchers.Main) {
                try {
                    if (isBound) {
                        context.applicationContext.unbindService(this@LoggerServiceConnection)
                    }
                } catch (e: Exception) { }

                isBound = false
                logControl = null
                _isConnected.value = false
            }
        }
    }
}
