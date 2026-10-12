package com.xuanhan.cellularcompanion.utilities

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.util.Log
import com.android.dx.stock.ProxyBuilder
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Created by jonro on 19/03/2018.
 */
class WifiHotspotManager(private val mContext: Context) {
    private val mConnectivityManager: ConnectivityManager = mContext.getSystemService(ConnectivityManager::class.java) as ConnectivityManager

    var isHotspotStartedByUs = false
        private set
    val isTetherActive: Boolean
        /**
         * Checks where tethering is on.
         * This is determined by the getTetheredIfaces() method,
         * that will return an empty array if not devices are tethered
         *
         * @return true if a tethered device is found, false if not found
         */
        get() {
            try {
                val method = mConnectivityManager.javaClass.getDeclaredMethod("getTetheredIfaces")
                val res = method.invoke(mConnectivityManager) as Array<String>
                Log.d(TAG, "getTetheredIfaces invoked")
                Log.d(TAG, res.contentToString())
                if (res.isNotEmpty()) {
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in getTetheredIfaces")
                e.printStackTrace()
            }
            return false
        }

    /**
     * This enables tethering using the ssid/password defined in Settings App>Hotspot & tethering
     * Does not require app to have system/privileged access
     * Credit: Vishal Sharma - https://stackoverflow.com/a/52219887
     */
    fun startTethering(callback: HotspotOnStartTetheringCallback): Boolean {

        // On Pie if we try to start tethering while it is already on, it will
        // be disabled. This is needed when startTethering() is called programmatically.
        if (isTetherActive) {
            Log.d(TAG, "Tether already active, returning")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return startTetheringWithTetheringManager(callback)
        }

        val outputDir = mContext.codeCacheDir
        val proxy = try {
            ProxyBuilder.forClass(onStartTetheringCallback())
                .dexCache(outputDir).handler { proxy, method, args ->
                    when (method.name) {
                        "onTetheringStarted" -> callback.onTetheringStarted()
                        "onTetheringFailed" -> callback.onTetheringFailed()
                        else -> ProxyBuilder.callSuper(proxy, method, *args)
                    }
                    null
                }.build()
        } catch (e: Exception) {
            Log.e(TAG, "Error in enableTethering ProxyBuilder")
            e.printStackTrace()
            return false
        }
        val method: Method?
        try {
            method = mConnectivityManager.javaClass.getDeclaredMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                onStartTetheringCallback(),
                Handler::class.java
            )
            if (method == null) {
                Log.e(TAG, "startTetheringMethod is null")
            } else {
                method.invoke(
                    mConnectivityManager,
                    ConnectivityManager.TYPE_MOBILE,
                    false,
                    proxy,
                    null
                )
                Log.d(TAG, "startTethering invoked")
            }
            isHotspotStartedByUs = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in enableTethering")
            e.printStackTrace()
        }
        return false
    }

    fun stopTethering() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val tetheringManager = mContext.getSystemService(TETHERING_SERVICE)!!
                tetheringManager.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
                    .invoke(tetheringManager, TETHERING_WIFI)
                isHotspotStartedByUs = false
                Log.d(TAG, "TetheringManager.stopTethering invoked")
            } catch (e: Exception) {
                Log.e(TAG, "TetheringManager.stopTethering error", e)
            }
            return
        }

        try {
            val method = mConnectivityManager.javaClass.getDeclaredMethod(
                "stopTethering",
                Int::class.javaPrimitiveType
            )
            method.invoke(mConnectivityManager, ConnectivityManager.TYPE_MOBILE)
            isHotspotStartedByUs = false
            Log.d(TAG, "stopTethering invoked")
        } catch (e: Exception) {
            Log.e(TAG, "stopTethering error: $e")
            e.printStackTrace()
        }
    }

    /**
     * This function starts Wi-Fi tethering through TetheringManager (Android 11+). Unlike the ConnectivityManager
     * wrapper, it reports why tethering failed, and its callback is an interface, so it can be implemented with a
     * plain Java proxy instead of generating code at runtime.
     */
    private fun startTetheringWithTetheringManager(callback: HotspotOnStartTetheringCallback): Boolean {
        try {
            val tetheringManager = mContext.getSystemService(TETHERING_SERVICE)!!

            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
            builderClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType).invoke(builder, false)
            val request = builderClass.getMethod("build").invoke(builder)!!

            val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val proxy = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
                when (method.name) {
                    "onTetheringStarted" -> {
                        Log.d(TAG, "Tethering started")
                        isHotspotStartedByUs = true
                        callback.onTetheringStarted()
                        null
                    }
                    "onTetheringFailed" -> {
                        // Since Android 16, starting tethering needs TETHER_PRIVILEGED, which only system apps can hold,
                        // so this fails with TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION (14) there
                        Log.e(TAG, "Tethering failed with error ${args?.getOrNull(0)}")
                        callback.onTetheringFailed()
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "StartTetheringCallback"
                    else -> null
                }
            }

            tetheringManager.javaClass
                .getMethod("startTethering", request.javaClass, Executor::class.java, callbackClass)
                .invoke(tetheringManager, request, mContext.mainExecutor, proxy)
            Log.d(TAG, "TetheringManager.startTethering invoked")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error in TetheringManager.startTethering", e)
            return false
        }
    }

    private fun onStartTetheringCallback(): Class<*>? {
        try {
            return Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "OnStartTetheringCallbackClass error: $e")
            e.printStackTrace()
        }
        return null
    }

    companion object {
        private val TAG = WifiHotspotManager::class.java.simpleName

        // Hidden constants from Context and TetheringManager
        private const val TETHERING_SERVICE = "tethering"
        private const val TETHERING_WIFI = 0
    }
}