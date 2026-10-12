package com.xuanhan.cellularcompanion.models

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * This object follows Shizuku, which lets this app call system services as the shell user. The shell user can start
 * tethering on Android 16+, where apps no longer can.
 */
object ShizukuModel {
    enum class Status {
        NotInstalled,
        NotRunning,
        PermissionNeeded,
        Ready
    }

    private const val PACKAGE_NAME = "moe.shizuku.privileged.api"

    private lateinit var appContext: Context
    private val _status = MutableStateFlow(Status.NotRunning)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * Whether Shizuku is running and this app may use it.
     */
    val isReady: Boolean
        get() {
            refresh()
            return _status.value == Status.Ready
        }

    /**
     * This function starts following Shizuku. It is safe to call more than once.
     * @param context Any context; only the application context is kept.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (::appContext.isInitialized) {
            refresh()
            return
        }
        appContext = context.applicationContext

        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    /**
     * This function updates the status, e.g. after the user returns from installing Shizuku.
     */
    @Synchronized
    fun refresh() {
        val status = try {
            when {
                !Shizuku.pingBinder() -> if (isInstalled()) Status.NotRunning else Status.NotInstalled
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Status.Ready
                else -> Status.PermissionNeeded
            }
        } catch (e: Exception) {
            // Shizuku stopped between the calls, or is too old to support permissions (before v11)
            println("Error: Could not get Shizuku's status: $e")
            Status.NotRunning
        }
        if (_status.value != status) {
            println("Shizuku status: $status")
            _status.value = status
        }
    }

    private fun isInstalled(): Boolean {
        if (!::appContext.isInitialized) return false
        return try {
            appContext.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * This function gets a system service whose calls are made by Shizuku, as the shell user.
     * @param name The name of the system service, e.g. "tethering".
     */
    fun getSystemService(name: String): IBinder {
        return ShizukuBinderWrapper(SystemServiceHelper.getSystemService(name))
    }
}
