package com.xuanhan.cellularcompanion.services

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.xuanhan.cellularcompanion.bluetoothModel
import com.xuanhan.cellularcompanion.broadcastreceivers.BluetoothStateBroadcastReceiver
import com.xuanhan.cellularcompanion.createBluetoothNotification
import com.xuanhan.cellularcompanion.isBluetoothEnabled
import com.xuanhan.cellularcompanion.models.UpdateModel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask
import kotlin.math.floor

/**
 * This service is used to manage the Bluetooth connection to the Mac.
 */
class BluetoothService : Service() {
    private val binder = BluetoothServiceBinder()
    private val btStateBroadcastReceiver = BluetoothStateBroadcastReceiver()
    private val batteryLevelTimer = Timer()
    private val getBatteryInfoCoroutineScope = CoroutineScope(Dispatchers.IO + CoroutineName("GetBatteryInfo"))
    private val getBluetoothStateCoroutineScope = CoroutineScope(Dispatchers.IO + CoroutineName("GetBluetoothState"))
    private val getNetworkInfoCoroutineScope = CoroutineScope(Dispatchers.IO + CoroutineName("GetNetworkInfo"))
    private val updateStatusNotificationCoroutineScope = CoroutineScope(Dispatchers.IO + CoroutineName("UpdateStatusNotification"))
    private val checkForUpdatesCoroutineScope = CoroutineScope(Dispatchers.IO + CoroutineName("CheckForUpdates"))
    private var prevModifiedSignalStrengthLevel = -1
    private var prevNetworkType = ""
    private var prevBatteryPercentage = -1
    private lateinit var telephonyManager: TelephonyManager
    private val telephonyCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener {
            override fun onSignalStrengthsChanged(p0: SignalStrength) {
                handleSignalStrengthLevelChanged(p0.level)
            }

            override fun onDisplayInfoChanged(p0: TelephonyDisplayInfo) {
                handleNetworkTypeChanged(p0.networkType, p0.overrideNetworkType)
            }
        }
    else
        object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(p0: SignalStrength) {
                handleSignalStrengthLevelChanged(p0.level)

                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    if (ActivityCompat.checkSelfPermission(
                            applicationContext,
                            Manifest.permission.READ_PHONE_STATE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        println("Error: Read phone state permission not granted")
                        return
                    }

                    val networkType = telephonyManager.networkType
                    handleNetworkTypeChanged(networkType, 0)
                }
            }

            @RequiresApi(Build.VERSION_CODES.R)
            override fun onDisplayInfoChanged(p0: TelephonyDisplayInfo) {
                handleNetworkTypeChanged(p0.networkType, p0.overrideNetworkType)
            }
        }
    private var started = false
    private var btStateBroadcastReceiverRegistered = false
    private var isSeePhoneInfoEnabled = false

    /**
     * This function notifies the Mac of a change in the signal strength level if needed, modifying the signal strength level for the Cellular app (0 to 3).
     * @param signalStrengthLevel The signal strength level reported by Android (0 to 4).
     */
    private fun handleSignalStrengthLevelChanged(signalStrengthLevel: Int) {
        val modifiedSignalStrengthLevel =
            floor(((signalStrengthLevel.toDouble() + 1) / 5 * 4) + 0.5).toInt() - 1
        if (modifiedSignalStrengthLevel != prevModifiedSignalStrengthLevel) {
            println("Modified level $modifiedSignalStrengthLevel")

            bluetoothModel.sharePhoneInfo(modifiedSignalStrengthLevel, "-1", -1)
        }
        prevModifiedSignalStrengthLevel = modifiedSignalStrengthLevel
    }

    /**
     * This function notifies the Mac of a change in the network type if needed, modifying the network type for the Cellular app.
     * @param networkType The network type reported by Android.
     * @param overrideNetworkType The override network type reported by Android.
     */
    private fun handleNetworkTypeChanged(networkType: Int, overrideNetworkType: Int) {
        val networkTypeString = if (overrideNetworkType == 0) {
            when (networkType) {
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_1xRTT -> "GPRS"

                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_IDEN,
                TelephonyManager.NETWORK_TYPE_GSM -> "E"

                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"

                TelephonyManager.NETWORK_TYPE_LTE,
                TelephonyManager.NETWORK_TYPE_IWLAN, 19 -> "4G"

                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                else -> ""
            }
        } else {
            when (overrideNetworkType) {
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "5Ge"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G"
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
                else -> ""
            }
        }

        if (networkTypeString != prevNetworkType) {
            println("Network type $networkTypeString")
            bluetoothModel.sharePhoneInfo(-1, networkTypeString, -1)
        }

        prevNetworkType = networkTypeString
    }

    /**
     * This function notifies the Mac of a change in the battery percentage if needed.
     * @param batteryPercentage The battery percentage reported by Android.
     * @param immediate Whether to immediately notify the Mac of the battery percentage.
     */
    private fun handleBatteryPercentageChanged(batteryPercentage: Int, immediate: Boolean) {
        if (isSeePhoneInfoEnabled) {
            if (batteryPercentage != prevBatteryPercentage || immediate) {
                println("Updating battery level $batteryPercentage")
                bluetoothModel.sharePhoneInfo(-1, "-1", batteryPercentage)
            }
        }

        prevBatteryPercentage = batteryPercentage
    }

    inner class BluetoothServiceBinder : Binder() {
        fun getService(): BluetoothService {
            return this@BluetoothService
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            println("Service started")

            // Update Bluetooth enabled state
            isBluetoothEnabled.value = (applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.isEnabled

            // Check for updates now and then once a day
            checkForUpdatesCoroutineScope.launch {
                while (true) {
                    UpdateModel.checkForUpdates(applicationContext)
                    delay(UpdateModel.CHECK_INTERVAL_MS)
                }
            }

            // Initialise telephony manager
            telephonyManager =
                applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            // Initialise Bluetooth model if Bluetooth is enabled
            if (isBluetoothEnabled.value) {
                getBatteryInfoCoroutineScope.launch {
                    println("Initialising Bluetooth model now...")
                    initBluetoothModel()
                }
            }

            getBluetoothStateCoroutineScope.launch {
                var prevBluetoothEnabled = isBluetoothEnabled.value

                // Listen for changes in Bluetooth enabled state
                isBluetoothEnabled.collect {
                    val notificationManager = applicationContext
                        .getSystemService(NOTIFICATION_SERVICE) as NotificationManager

                    if (it && !prevBluetoothEnabled) {
                        // Re-initialise Bluetooth model if Bluetooth has become enabled
                        println("Reinitialising Bluetooth model now...")
                        initBluetoothModel()
                    } else if (!it && prevBluetoothEnabled) {
                        // Disconnect from Mac if Bluetooth has become disabled
                        bluetoothModel.disconnect()
                    }

                    if (ActivityCompat.checkSelfPermission(
                            this@BluetoothService,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        // Update status notification
                        notificationManager.notify(
                            1,
                            createBluetoothNotification(
                                connectStatus = bluetoothModel.connectStatus.value,
                                context = applicationContext
                            )
                        )
                    }

                    prevBluetoothEnabled = it
                }
            }

            getNetworkInfoCoroutineScope.launch {
                // Listen for changes in the "See phone info" setting
                bluetoothModel.isSeePhoneInfoEnabled.collect {
                    isSeePhoneInfoEnabled = it

                    if (isSeePhoneInfoEnabled) {
                        // Start sharing phone info
                        startSharePhoneInfo()

                        // Share battery info immediately
                        getBatteryPercentage(immediate = true)
                    } else {
                        // Stop sharing phone info and reset all phone info values
                        prevModifiedSignalStrengthLevel = -1
                        prevNetworkType = ""
                        prevBatteryPercentage = -1
                        disposePhoneStateListeners()
                    }
                }
            }

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                updateStatusNotificationCoroutineScope.launch {
                    // Listen for changes in the connect status
                    bluetoothModel.connectStatus.collect {
                        val notificationManager = applicationContext
                            .getSystemService(NOTIFICATION_SERVICE) as NotificationManager

                        // Update status notification
                        notificationManager.notify(1, createBluetoothNotification(connectStatus = it, context = applicationContext))
                    }
                }
            }

            // Start service as a foreground service with the status notification
            startForeground(1, createBluetoothNotification(connectStatus = bluetoothModel.connectStatus.value, context = applicationContext))

            // Register a new broadcast receiver to listen for changes in the Bluetooth enabled state
            if (btStateBroadcastReceiverRegistered) {
                unregisterReceiver(btStateBroadcastReceiver)
            }
            registerReceiver(btStateBroadcastReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

            btStateBroadcastReceiverRegistered = true
            started = true
        }

        return START_STICKY
    }

    /**
     * This function initialises the Bluetooth model and starts retrieving battery percentage every 5 min.
     */
    private suspend fun initBluetoothModel() {
        bluetoothModel.initializeFromDataStore(
            {
                batteryLevelTimer.scheduleAtFixedRate(object : TimerTask() {
                    override fun run() {
                        getBatteryPercentage()
                    }
                }, 0, 300000)
            }, applicationContext
        )
    }

    /**
     * This function retrieves the current battery percentage.
     * @param immediate Whether to immediately notify the Mac of the battery percentage.
     */
    private fun getBatteryPercentage(immediate: Boolean = false) {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let {
            applicationContext.registerReceiver(null, it)
        }
        val batteryPct: Int? = batteryStatus?.let { intent ->
            val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            (level * 100 / scale.toFloat()).toInt()
        }
        println("Battery percentage $batteryPct")
        handleBatteryPercentageChanged(batteryPct ?: 100, immediate)
    }

    /**
     * This function starts sharing phone info.
     */
    private fun startSharePhoneInfo() {
        println("Start share phone info")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(
                applicationContext.mainExecutor,
                telephonyCallback as TelephonyCallback
            )
        } else {
            telephonyManager.listen(
                telephonyCallback as PhoneStateListener,
                PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
            )
        }
    }

    /**
     * This function disposes the listeners related to sharing phone info.
     */
    private fun disposePhoneStateListeners() {
        println("Stop share phone info")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.unregisterTelephonyCallback(telephonyCallback as TelephonyCallback)
        } else {
            telephonyManager.listen(
                telephonyCallback as PhoneStateListener,
                PhoneStateListener.LISTEN_NONE
            )
        }
    }

    override fun onDestroy() {
        if (started) {
            batteryLevelTimer.cancel()
            disposePhoneStateListeners()
            this.unregisterReceiver(btStateBroadcastReceiver)
            getBatteryInfoCoroutineScope.cancel()
            getBluetoothStateCoroutineScope.cancel()
            getNetworkInfoCoroutineScope.cancel()
            updateStatusNotificationCoroutineScope.cancel()
            checkForUpdatesCoroutineScope.cancel()
            prevModifiedSignalStrengthLevel = -1
            prevNetworkType = ""
            prevBatteryPercentage = -1
            isSeePhoneInfoEnabled = false
            started = false

            println("Service stopped")
        }

        super.onDestroy()
    }
}