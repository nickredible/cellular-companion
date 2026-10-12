package com.xuanhan.cellularcompanion.models

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.ActivityCompat
import com.xuanhan.cellularcompanion.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * This object checks GitHub for a newer release of this app. Updates are not installed automatically: the user is
 * pointed to the release page to download it.
 */
object UpdateModel {
    data class Release(val version: String, val url: String)

    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/nickredible/cellular-companion/releases/latest"
    private const val TIMEOUT_MS = 15_000

    // Kept apart from the data store, which is cleared when the Mac is unlinked
    private const val PREFERENCES_NAME = "updates"
    private const val CHECK_FOR_UPDATES_KEY = "checkForUpdates"
    private const val LAST_NOTIFIED_VERSION_KEY = "lastNotifiedVersion"

    private const val UPDATE_CHANNEL_ID = "updates"
    private const val UPDATE_NOTIFICATION_ID = 3

    private val _availableUpdate = MutableStateFlow<Release?>(null)

    /**
     * The newest release, if it is newer than this version of the app.
     */
    val availableUpdate: StateFlow<Release?> = _availableUpdate.asStateFlow()

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the user allows this app to check for updates.
     */
    fun isCheckingEnabled(context: Context) = preferences(context).getBoolean(CHECK_FOR_UPDATES_KEY, true)

    fun setCheckingEnabled(context: Context, isEnabled: Boolean) {
        preferences(context).edit().putBoolean(CHECK_FOR_UPDATES_KEY, isEnabled).apply()
        if (!isEnabled) {
            _availableUpdate.value = null
        }
    }

    /**
     * This function checks GitHub for a newer release, if the user allows it, and notifies the user the first time each
     * new version is found.
     */
    suspend fun checkForUpdates(context: Context) = withContext(Dispatchers.IO) {
        if (!isCheckingEnabled(context)) {
            _availableUpdate.value = null
            return@withContext
        }

        val release = try {
            fetchLatestRelease()
        } catch (e: Exception) {
            println("Failed to check for updates: $e")
            null
        } ?: return@withContext

        val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        if (!isVersionNewer(release.version, currentVersion)) {
            _availableUpdate.value = null
            return@withContext
        }

        println("Cellular Companion ${release.version} is available")
        _availableUpdate.value = release

        // Only notify once per version, since this is checked every day
        val preferences = preferences(context)
        if (preferences.getString(LAST_NOTIFIED_VERSION_KEY, null) == release.version) return@withContext
        preferences.edit().putString(LAST_NOTIFIED_VERSION_KEY, release.version).apply()
        showUpdateNotification(context, release)
    }

    private fun fetchLatestRelease(): Release? {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS

            // A repository without releases returns 404, which needs no attention
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return Release(json.getString("tag_name").removePrefix("v"), json.getString("html_url"))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * This function compares two version numbers like "1.2.10" component by component, treating missing components as 0.
     */
    fun isVersionNewer(version: String, otherVersion: String): Boolean {
        val components = version.split(".").map { it.toIntOrNull() ?: 0 }
        val otherComponents = otherVersion.split(".").map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(components.size, otherComponents.size)) {
            val component = components.getOrElse(index) { 0 }
            val otherComponent = otherComponents.getOrElse(index) { 0 }
            if (component != otherComponent) {
                return component > otherComponent
            }
        }
        return false
    }

    /**
     * This function opens the release page of an update.
     */
    fun releaseIntent(release: Release) = Intent(Intent.ACTION_VIEW, Uri.parse(release.url))

    private fun showUpdateNotification(context: Context, release: Release) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            println("Error: The Post Notifications permission has not been granted.")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(UPDATE_CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Lets you know when a new version of Cellular Companion is available."
        notificationManager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            context, 0, releaseIntent(release), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, UPDATE_CHANNEL_ID)
            .setContentTitle("Cellular Companion ${release.version} is available")
            .setContentText("Tap to download it from GitHub.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(UPDATE_NOTIFICATION_ID, notification)
    }
}
