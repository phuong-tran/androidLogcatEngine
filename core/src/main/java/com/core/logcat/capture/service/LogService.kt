package com.core.logcat.capture.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.core.logcat.capture.ILogControl
import com.core.logcat.capture.core.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * LogcatService: A background service responsible for hosting the LogManager engine.
 * By running in a Service, log collection remains stable across configuration changes (like rotations).
 */
class LogcatService : Service() {

    /**
     * Service-linked Coroutine Scope.
     * Uses SupervisorJob to ensure that a failure in one task doesn't kill the entire service.
     */
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * AIDL STUB IMPLEMENTATION
     * This defines the remote procedures that the UI layer (via LoggerServiceConnection) can call.
     */
    private val binder = object : ILogControl.Stub() {

        /**
         * Initiates the native log capture.
         * Automatically detects the current Application PID to filter logs specifically for this app.
         */
        override fun startLogging(tags: String?, regex: String?) {
            serviceScope.launch {
                // Get the PID of the current process to focus logcat on this app only
                val myPid = Process.myPid().toString()
                LogManager.startNative(
                    pid = myPid,
                    tags = tags.orEmpty(),
                    lv = "V", // Defaulting to Verbose to capture everything
                    reg = regex.orEmpty()
                )
            }
        }

        /**
         * Proxies filter update requests to the LogManager.
         */
        override fun updateFilters(tags: String?, regex: String?) {
            serviceScope.launch {
                regex?.let { LogManager.updateRegexFilter(it) }
            }
        }

        /**
         * Proxies literal search updates to the LogManager.
         */
        override fun updateLiteral(text: String?) {
            serviceScope.launch {
                text?.let { LogManager.updatePlainTextFilter(it) }
            }
        }

        /**
         * Stops the native logging engine asynchronously.
         */
        override fun stopLogging() {
            serviceScope.launch {
                LogManager.stopNative()
            }
        }
    }

    /**
     * Return the AIDL binder to the binding client (LoggerServiceConnection).
     */
    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * START_STICKY ensures the system attempts to recreate the service
     * if it gets killed due to low memory.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * CLEANUP PHASE
     * Triggered when the service is explicitly stopped or destroyed by the system.
     * Ensures no native processes or coroutines are leaked.
     */
    override fun onDestroy() {
        // Shutdown the native engine immediately
        LogManager.stopNative()

        // Cancel all pending coroutines in this service
        serviceScope.cancel()

        super.onDestroy()
    }
}
