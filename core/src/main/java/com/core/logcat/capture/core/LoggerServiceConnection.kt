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
 * LoggerServiceConnection: Manages the lifecycle of the connection to LogcatService.
 * Uses AIDL (ILogControl) for Inter-Process Communication (IPC).
 */
@Suppress("unused")
object LoggerServiceConnection : ServiceConnection {
    // Interface generated from AIDL to control the background service
    private var logControl: ILogControl? = null

    /**
     * Dedicated Coroutine Scope for AIDL/IPC calls.
     * Prevents blocking the Main Thread during remote service communication.
     */
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Observable state to notify UI about the service connection status
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    /**
     * Commands the remote service to begin log collection.
     */
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
     * Hot-swaps Regex filters in the background service.
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

    /**
     * Hot-swaps Literal/Plain-text search filters in the background service.
     */
    fun updateLiteralSearch(text: String): Boolean {
        if (!_isConnected.value) return false
        connectionScope.launch {
            try {
                logControl?.updateLiteral(text)
            } catch (e: Exception) { }
        }
        return true
    }

    /**
     * Called by the Android system when the connection to the service is established.
     */
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        logControl = ILogControl.Stub.asInterface(binder)
        _isConnected.value = true
    }

    /**
     * Called when the service process crashes or is killed by the system.
     */
    override fun onServiceDisconnected(name: ComponentName?) {
        logControl = null
        _isConnected.value = false
    }

    /**
     * Initiates the binding process to the LogcatService.
     */
    fun bind(context: Context) {
        val appContext = context.applicationContext
        val intent = Intent(appContext, LogcatService::class.java)
        appContext.startService(intent)
        appContext.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    /**
     * Graceful Shutdown Procedure:
     * 1. Signals Native Core to stop and release resources.
     * 2. Unbinds and stops the Android Service on the Main Thread.
     */
    fun unbind(context: Context) {
        connectionScope.launch {
            // Phase 1: Request remote cleanup and wait for the signal to propagate
            if (_isConnected.value) {
                try {
                    logControl?.stopLogging()
                    // Brief delay to ensure the 'stop' command reaches the service before unbinding
                    delay(100)
                } catch (e: Exception) { }
            }

            // Phase 2: Perform UI-thread unbinding and service termination
            withContext(Dispatchers.Main) {
                try {
                    context.applicationContext.unbindService(this@LoggerServiceConnection)
                    val intent = Intent(context.applicationContext, LogcatService::class.java)
                    context.applicationContext.stopService(intent)
                } catch (e: Exception) { }

                logControl = null
                _isConnected.value = false
            }
        }
    }
}
