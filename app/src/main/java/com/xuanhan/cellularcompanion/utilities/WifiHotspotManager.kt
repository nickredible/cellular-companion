package com.xuanhan.cellularcompanion.utilities

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.dx.stock.ProxyBuilder
import com.xuanhan.cellularcompanion.models.ShizukuModel
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor
import java.util.function.Supplier

/**
 * Created by jonro on 19/03/2018.
 */
class WifiHotspotManager(private val mContext: Context) {
    private val mConnectivityManager: ConnectivityManager = mContext.getSystemService(ConnectivityManager::class.java) as ConnectivityManager

    var isHotspotStartedByUs = false
        private set

    // A TetheringManager whose calls are made through Shizuku, and the Shizuku binder it was made with
    private var shizukuTetheringManager: Any? = null
    private var shizukuTetheringManagerBinder: IBinder? = null

    init {
        ShizukuModel.initialize(mContext)
    }

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
                val tetheringManager = getTetheringManager()
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
    @RequiresApi(Build.VERSION_CODES.R)
    private fun startTetheringWithTetheringManager(callback: HotspotOnStartTetheringCallback): Boolean {
        try {
            val tetheringManager = getTetheringManager()

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
                        // Since Android 16, starting tethering needs TETHER_PRIVILEGED, which only system apps and the
                        // shell user can hold, so without Shizuku this fails with
                        // TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION (14) there
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

    /**
     * This function gets the TetheringManager to start and stop tethering with: one that goes through Shizuku if it can
     * be used, since only the shell user and system apps may start tethering on Android 16+, or this app's own
     * otherwise.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun getTetheringManager(): Any {
        if (ShizukuModel.isReady) {
            try {
                return getShizukuTetheringManager()
            } catch (e: Exception) {
                Log.e(TAG, "Error creating a TetheringManager that goes through Shizuku", e)
            }
        }
        return mContext.getSystemService(TETHERING_SERVICE)!!
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun getShizukuTetheringManager(): Any {
        // A new TetheringManager registers itself with the tethering service, so only make one per Shizuku connection
        val shizukuBinder = Shizuku.getBinder()
        val cached = shizukuTetheringManager
        if (cached != null && shizukuTetheringManagerBinder === shizukuBinder) {
            return cached
        }

        // The tethering service checks that the package name it is given belongs to the calling user, which is shell
        val shellContext = object : ContextWrapper(mContext.applicationContext) {
            override fun getOpPackageName() = SHELL_PACKAGE_NAME
            override fun getAttributionTag(): String? = null
        }
        val connector = ShizukuModel.getSystemService(TETHERING_SERVICE)
        // This constructor is a blocked hidden API, unlike the rest of TetheringManager used here
        val tetheringManager = HiddenApiBypass
            .getDeclaredConstructor(Class.forName("android.net.TetheringManager"), Context::class.java, Supplier::class.java)
            .newInstance(shellContext, Supplier<IBinder> { connector })!!
        Log.d(TAG, "Created a TetheringManager that goes through Shizuku")

        shizukuTetheringManager = tetheringManager
        shizukuTetheringManagerBinder = shizukuBinder
        return tetheringManager
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

        private const val SHELL_PACKAGE_NAME = "com.android.shell"
    }
}