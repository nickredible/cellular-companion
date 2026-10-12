package com.xuanhan.cellularcompanion.models

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.xuanhan.cellularcompanion.R
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xuanhan.cellularcompanion.broadcastreceivers.BondStateBroadcastReceiver
import com.xuanhan.cellularcompanion.isSetupComplete
import com.xuanhan.cellularcompanion.isSetupCompleteKey
import com.xuanhan.cellularcompanion.utilities.AES
import com.xuanhan.cellularcompanion.utilities.HotspotOnStartTetheringCallback
import com.xuanhan.cellularcompanion.utilities.WifiHotspotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * This class handles all things related to connecting to and communicating with the Cellular app on the Mac.
 * */
class BluetoothModel {
    private val operationQueue = ConcurrentLinkedQueue<() -> Unit>()
    private var isRunningOperation = false
    private val serviceUUIDKey = stringPreferencesKey("serviceUUID")
    private val sharedKeyKey = stringPreferencesKey("sharedKey")
    private val deviceAddressKey = stringPreferencesKey("deviceAddress")

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var context: Context
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var aes: AES
    private lateinit var wifiHotspotManager: WifiHotspotManager
    private val commandCharacteristicUUID = "00000001-0000-1000-8000-00805f9b34fb"
    private val notificationCharacteristicUUID = "00000002-0000-1000-8000-00805f9b34fb"
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var serviceUUID: String? = null
    private var sharedKey: String? = null
    private var deviceAddress: String? = null
    private var connectDevice: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationCharacteristic: BluetoothGattCharacteristic? = null
    private var bleScanTimer: Timer? = null
    private var isScanning = false
    private var isConnecting = false
    private var isDisconnecting = false
    private var connectionRetryCount = 0
    private var hotspotShareRetryCount = 0

    private var isFirstInitializing = false
    private var isInitializing = false
    private var initOnConnectCallback: (() -> Unit)? = null
    private var isSharingHotspotDetails = false
    private var shareHotspotDetails2: (() -> Unit)? = null
    private var onHotspotDetailsSharedCallback: (() -> Unit)? = null
    private var isSharingPhoneInfo = false
    private var sharePhoneInfo2: (() -> Unit)? = null
    private var isConnectingToHotspot = false
    private var connectHotspot2: (() -> Unit)? = null
    private var isDisconnectingFromHotspot = false
    private var disconnectHotspot2: (() -> Unit)? = null
    private var isIndicatingReset = false
    private var indicateReset: (() -> Unit)? = null

    private var onScanFailedCallback: ((() -> Unit) -> Unit)? = null
    private var onUnexpectedErrorCallback: ((() -> Unit) -> Unit)? = null
    private var onConnectFailedCallback: ((() -> Unit) -> Unit)? = null
    private var onBondFailedCallback: ((() -> Unit) -> Unit)? = null
    private var onHotspotDetailsShareFailedCallback: ((() -> Unit) -> Unit)? = null
    private var onResetFailedCallback: ((() -> Unit) -> Unit)? = null
    private var onResetCompleteCallback: (() -> Unit)? = null

    private var hotspotDetailsRetryCallback: (() -> Unit)? = null

    private val _connectStatus: MutableStateFlow<ConnectStatus> =
        MutableStateFlow(ConnectStatus.Disconnected)
    val connectStatus: StateFlow<ConnectStatus> = _connectStatus.asStateFlow()
    private val _isSeePhoneInfoEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isSeePhoneInfoEnabled: StateFlow<Boolean> = _isSeePhoneInfoEnabled.asStateFlow()
    private val _isAwaitingUserHotspot: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * Whether the user has been asked to turn on the hotspot themselves, because this app isn't allowed to (Android 16+).
     * */
    val isAwaitingUserHotspot: StateFlow<Boolean> = _isAwaitingUserHotspot.asStateFlow()
    private var awaitingUserHotspotTimer: Timer? = null
    private var isHotspotStateReceiverRegistered = false

    class NotificationType {
        companion object {
            const val EnableHotspot = "0"
            const val DisableHotspot = "1"
            const val IndicateConnectedHotspot = "2"
            const val EnableSeePhoneInfo = "3"
            const val DisableSeePhoneInfo = "4"
            const val IndicateReset = "5"
        }
    }

    class CommandType {
        companion object {
            const val HelloWorld = "0"
            const val ShareHotspotDetails = "1"
            const val SharePhoneInfo = "2"
            const val ConnectToHotspot = "3"
            const val DisconnectFromHotspot = "4"
            const val IndicateReset = "5"
        }
    }

    companion object {
        // Hidden constants from WifiManager. The broadcast is protected, so only the system can send it.
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
        private const val WIFI_AP_STATE_DISABLED = 11
        private const val WIFI_AP_STATE_ENABLED = 13

        private const val HOTSPOT_REQUEST_CHANNEL_ID = "hotspot_requests"
        private const val HOTSPOT_REQUEST_NOTIFICATION_ID = 2
        private const val AWAITING_USER_HOTSPOT_TIMEOUT_MS = 120_000L

        /**
         * An intent that opens the system's "Hotspot & tethering" settings.
         * */
        fun hotspotSettingsIntent() = Intent("android.settings.TETHER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        enum class ConnectStatus {
            Disconnected,
            Idle,
            Connecting,
            Connected
        }
    }

    /**
     * This callback handles results during BLE scans.
     * */
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            if (isScanning) {
                with(result.device) {
                    // Check for Bluetooth Connect permission on Android 12+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            println("Error: The Bluetooth Connect permission has not been granted.")
                            return
                        }
                    }

                    // Keep scanning if this isn't the paired Mac
                    if (!isFirstInitializing && address != deviceAddress) {
                        println("Error: Device address $address does not match paired device address.")
                        return
                    }

                    // Stop scan
                    stopScan()

                    println("Found Bluetooth device: ${result.scanRecord?.serviceUuids} $name $address")
                    println("Now attempting to connect to Bluetooth device.")

                    // Connect to the device, keeping the client so that it can be closed if the connection fails
                    isConnecting = true
                    connectDevice = this
                    this@BluetoothModel.gatt = connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            when (errorCode) {
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> {
                    onScanFailedCallback?.invoke {
                        resetBLE()
                        initialize(context)
                    }
                    println("Scan failed: restart Bluetooth and press Retry")
                }

                else -> {
                    onScanFailedCallback?.invoke {
                        resetBLE()
                        initialize(context)
                    }
                    println("Scan failed with error code $errorCode")
                }
            }
        }
    }

    /**
     * This callback handles results during GATT connections.
     * */
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt?,
            status: Int,
            newState: Int
        ) {
            super.onConnectionStateChange(gatt, status, newState)

            // Check for Bluetooth Connect permission on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    println("Error: The Bluetooth Connect permission has not been granted.")
                    return
                }
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Reset connection retry counter
                connectionRetryCount = 0

                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    // Save GATT device for later reference
                    this@BluetoothModel.gatt = gatt

                    isConnecting = false

                    println("Connected to device ${gatt!!.device.address}")

                    // Start discovering services of GATT device
                    gatt.discoverServices()
                } else {
                    // Indicate successful disconnection
                    onDisconnected()
                }
            } else {
                if (isConnecting) {
                    // Retry connection if it fails (up to 3 times)
                    // Close the failed client: Android only has a limited number of them
                    gatt?.close()

                    if (connectionRetryCount < 3) {
                        connectionRetryCount++
                        this@BluetoothModel.gatt = connectDevice!!.connectGatt(
                            context,
                            false,
                            this,
                            BluetoothDevice.TRANSPORT_LE
                        )
                        println("GATT connection failed but will retry: $status")
                    } else {
                        // Run connect failed callback only during setup
                        if (isFirstInitializing) {
                            onConnectFailedCallback?.invoke {
                                resetBLE()
                                initialize(context)
                            }
                        }

                        isConnecting = false
                        this@BluetoothModel.gatt = null

                        // Start scan for devices again
                        startScan()

                        println("Error: GATT connection failed with status $status")
                    }
                } else if (isDisconnecting && connectionRetryCount < 3) {
                    // Retry disconnection if it fails (up to 3 times)
                    connectionRetryCount++
                    gatt!!.disconnect()
                    println("GATT disconnection failed but will retry: $status")
                } else {
                    isDisconnecting = false

                    // Indicate disconnection
                    onDisconnected()

                    println("Error: GATT disconnection failed with status $status")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            this@BluetoothModel.gatt = gatt

            if (status == BluetoothGatt.GATT_SUCCESS) {
                println("Discovered ${gatt!!.services.size} services for device ${gatt.device.address}")

                // Check if the device has the Cellular service
                if (gatt.services.find { it.uuid.toString() == serviceUUID } != null) {
                    // Check for Bluetooth Connect permission on Android 12+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            println("Error: The Bluetooth Connect permission has not been granted.")
                            return
                        }
                    }

                    println("Found Cellular service!")

                    // Store the reference to the characteristics for the Cellular service
                    commandCharacteristic = getCharacteristicForDevice(commandCharacteristicUUID)
                    notificationCharacteristic =
                        getCharacteristicForDevice(notificationCharacteristicUUID)

                    // Request for a larger maximum transmission unit (MTU) size
                    gatt.requestMtu(517)
                } else {
                    onUnexpectedErrorCallback?.invoke {
                        resetBLE()
                        initialize(context)
                    }
                    println("Could not find service.")
                }
            } else {
                onUnexpectedErrorCallback?.invoke {
                    resetBLE()
                    initialize(context)
                }
                println("Service discovery failed due to internal error: $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                println("MTU changed to $mtu")

                if (isFirstInitializing) {
                    // Check for Bluetooth Connect permission on Android 12+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            println("Error: The Bluetooth Connect permission has not been granted.")
                            return
                        }
                    }

                    // Pair with device if needed
                    if (connectDevice!!.bondState != BluetoothDevice.BOND_BONDED) {
                        connectDevice!!.createBond()

                        // Connect broadcast receiver to receive updates
                        val intentFilter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                        context.registerReceiver(BondStateBroadcastReceiver(), intentFilter)
                    } else {
                        onBonded()
                    }
                    return
                }

                // Subscribe to notifications on the notification characteristic. This must happen on every
                // connection, because the Mac only considers this device connected once it has subscribed. Any
                // operation that was waiting for the connection resumes once the subscription is written.
                enableNotifications()
            } else {
                onUnexpectedErrorCallback?.invoke {
                    resetBLE()
                    initialize(context)
                }
                println("Error: MTU change failed with status $status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                println("Write to descriptor of characteristic ${descriptor!!.characteristic.uuid}")

                // Indicate that device is connected
                _connectStatus.value = ConnectStatus.Idle

                if (isInitializing) {
                    // Indicate that initialization is complete
                    initOnConnectCallback?.invoke()
                    indicateOperationComplete()
                } else {
                    // Resume an operation that was waiting for the connection
                    resumePendingOperation()
                }

                // Announce the connection with phone info that carries no values. If this app restarted without the
                // Bluetooth link dropping, the Mac still considers it subscribed and won't be told about this
                // subscription, so this lets it re-sync. Older versions of the Mac app accept and ignore it.
                if (isSetupComplete) {
                    sharePhoneInfo(-1, "-1", -1)
                }
            } else {
                println("Error: Failed to write to descriptor of characteristic ${descriptor?.characteristic?.uuid} with status $status")
                if (!isDisconnecting) {
                    // Indicate that device is disconnected
                    _connectStatus.value = ConnectStatus.Disconnected
                }

                // Without the subscription the Mac never considers this device connected, so start over (unless
                // this was the unsubscription while unlinking)
                if (isSetupComplete && !isDisconnecting) {
                    disconnect()
                    resetBLE()
                    startScan()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                println("Wrote to characteristic ${characteristic!!.uuid}")

                // Run the next operation
                if (isFirstInitializing) {
                    // Store device address
                    deviceAddress = connectDevice!!.address
                    CoroutineScope(Dispatchers.IO).launch {
                        context.dataStore.edit { settings ->
                            settings[deviceAddressKey] = deviceAddress!!
                        }
                    }

                    // Indicate that initialization is complete
                    initOnConnectCallback?.invoke()
                } else if (isSharingHotspotDetails) {
                    // Indicate that hotspot details are shared
                    onHotspotDetailsSharedCallback?.invoke()
                } else if (isConnectingToHotspot) {
                    // Indicate that hotspot is connected
                    _connectStatus.value = ConnectStatus.Connected
                } else if (isIndicatingReset) {
                    // Complete reset
                    reset2()
                    return
                }

                indicateOperationComplete()
            } else {
                println("Error: Failed to write to characteristic ${characteristic?.uuid} with status $status")
                onCommandWriteFailed()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(characteristic, value)
        }

        @Deprecated("Only called before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic != null) {
                handleNotification(characteristic, characteristic.value)
            }
        }

        private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val text = String(value)
            println("Received data from characteristic ${characteristic.uuid}: $text")

            when (text) {
                NotificationType.EnableHotspot -> {
                    println("Enabling hotspot...")
                    enableHotspot()
                }

                NotificationType.DisableHotspot -> {
                    println("Disabling hotspot...")
                    disableHotspot(indicateOnly = true)
                }

                NotificationType.IndicateConnectedHotspot -> {
                    println("Indicating device connected to hotspot...")
                    indicateConnectedHotspot()
                }

                NotificationType.EnableSeePhoneInfo -> {
                    println("Enabling see phone info...")
                    _isSeePhoneInfoEnabled.value = true
                }

                NotificationType.DisableSeePhoneInfo -> {
                    println("Disabling see phone info...")
                    _isSeePhoneInfoEnabled.value = false
                }

                NotificationType.IndicateReset -> {
                    println("Unlinking from Mac...")
                    reset(indicateOnly = true)
                }

                else -> {
                    println("Error: Received unknown payload from characteristic.")
                }
            }
        }
    }

    /**
     * This function handles a successful bond.
     * */
    fun onBonded() {
        // Continue next part of setup (sending Hello World command)
        initialize2()
    }

    /**
     * This function handles a bond failure.
     * */
    fun onBondingFailed() {
        println("Error: Failed to bond to device ${connectDevice!!.address}")

        val initOnConnectCallback = initOnConnectCallback
        onBondFailedCallback?.invoke {
            resetBLE()
            isFirstInitializing = true
            this@BluetoothModel.initOnConnectCallback = initOnConnectCallback
            initialize(context)
        }
    }

    private val myHotspotOnStartTetheringCallback = object : HotspotOnStartTetheringCallback() {
        override fun onTetheringStarted() {
            this@BluetoothModel.onTetheringStarted()
        }

        override fun onTetheringFailed() {
            println("Hotspot failed to start")
            requestHotspotFromUser()
        }
    }

    /**
     * This function handles when the hotspot is enabled. There is a delay of 3 seconds before the Mac is notified that the hotspot is enabled.
     * */
    private fun onTetheringStarted() {
        println("Hotspot started")

        val timer = Timer()
        timer.schedule(object : TimerTask() {
            override fun run() {
                // Check for Bluetooth Connect permission on Android 12+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        println("Error: The Bluetooth Connect permission has not been granted.")
                        return
                    }
                }

                // Notify Mac to connect to hotspot
                enqueueOperation {
                    isConnectingToHotspot = true

                    connectHotspot2 = {
                        writeCommand(CommandType.ConnectToHotspot)
                    }

                    if (!isReady()) {
                        ensureConnection()
                    } else {
                        connectHotspot2!!.invoke()
                    }
                }
            }
        }, 3000)
    }

    /**
     * This function handles when the Mac is disconnected from this device (Bluetooth).
     * @param noRestartScan Whether to restart the scan for the Mac.
     * */
    @SuppressLint("MissingPermission")
    private fun onDisconnected(noRestartScan: Boolean = false) {
        if (isSetupComplete) {
            // Set connect status to disconnected
            _connectStatus.value = ConnectStatus.Disconnected

            // Disable notifications for characteristic if needed
            val notificationCharacteristic = notificationCharacteristic
            if (gatt != null && notificationCharacteristic != null) {
                gatt!!.setCharacteristicNotification(notificationCharacteristic, false)
            }

            // Operations queued for the old connection are stale: the hotspot is turned off below, and phone info is
            // resent when the Mac re-enables it after reconnecting
            if (!isInitializing) {
                abandonOperations()
            }

            // Disable hotspot if enabled
            disableHotspot(indicateOnly = true, isDisconnecting = true)

            // Disable see phone info
            _isSeePhoneInfoEnabled.value = false

            // Start scan for devices if disconnected due to issues such as out of range, device powered off etc. if needed
            if (!noRestartScan) {
                startScan()
            }
        }

        println("Disconnected from device advertising: ${gatt?.device?.address}")
    }

    /**
     * This function adds a new Bluetooth operation to the queue (because BLE operations must be run one at a time).
     * @param operation The operation to add to the queue.
     * */
    @Synchronized
    private fun enqueueOperation(operation: () -> Unit) {
        operationQueue.add(operation)
        if (!isRunningOperation) {
            doNextOperation()
        }
    }

    /**
     * This function starts the next Bluetooth operation. This function should only be called by [enqueueOperation] or [indicateOperationComplete].
     * */
    @Synchronized
    private fun doNextOperation() {
        operationQueue.poll()?.let {
            isRunningOperation = true

            it.invoke()
        }
    }

    /**
     * This function indicates the completion of a Bluetooth operation. This function must be called after the completion of each Bluetooth operation, whether it succeeded or failed.
     * */
    @Synchronized
    private fun indicateOperationComplete() {
        isRunningOperation = false

        // Reset indicators of commands
        isFirstInitializing = false
        isInitializing = false
        initOnConnectCallback = null
        isSharingHotspotDetails = false
        isSharingPhoneInfo = false
        isConnectingToHotspot = false
        isDisconnectingFromHotspot = false
        isIndicatingReset = false
        onHotspotDetailsSharedCallback = null

        doNextOperation()
    }

    /**
     * This function handles a write to the command characteristic that failed or could not be started.
     * */
    private fun onCommandWriteFailed() {
        if (isFirstInitializing) {
            val initOnConnectCallback = initOnConnectCallback
            onConnectFailedCallback?.invoke {
                resetBLE()
                isFirstInitializing = true
                this@BluetoothModel.initOnConnectCallback = initOnConnectCallback
                initialize(context)
            }
        } else if (isSharingHotspotDetails) {
            if (hotspotShareRetryCount < 5) {
                // Retry sharing hotspot details to device. Count first: a write that can't start fails synchronously.
                hotspotShareRetryCount++
                shareHotspotDetails2?.invoke()
            } else {
                // Show error message
                hotspotDetailsRetryCallback?.let {
                    onHotspotDetailsShareFailedCallback?.invoke(it)
                }
            }
        } else if (isIndicatingReset) {
            onResetFailedCallback?.invoke {
                resetBLE()
                reset()
            }
        } else {
            // Drop the failed operation so that the queue doesn't stay blocked forever, then reconnect.
            // State is re-synced with the Mac once it reconnects.
            abandonOperations()
            disconnect()
            resetBLE()
            startScan()
        }
    }

    /**
     * This function drops the running operation and every queued operation.
     * */
    @Synchronized
    private fun abandonOperations() {
        operationQueue.clear()
        indicateOperationComplete()
    }

    /**
     * This function resumes an operation that was waiting for the connection to the Mac to be established.
     * */
    private fun resumePendingOperation() {
        when {
            isSharingHotspotDetails -> shareHotspotDetails2?.invoke()
            isSharingPhoneInfo -> sharePhoneInfo2?.invoke()
            isConnectingToHotspot -> connectHotspot2?.invoke()
            isDisconnectingFromHotspot -> disconnectHotspot2?.invoke()
            isIndicatingReset -> indicateReset?.invoke()
        }
    }

    /**
     * This function writes a command to the Mac's command characteristic. If the write can't be started, it is
     * handled like a failed write so that the operation queue never waits for a callback that won't come.
     * @param payload The command to write.
     * */
    @SuppressLint("MissingPermission")
    private fun writeCommand(payload: String) {
        val gatt = gatt
        val commandCharacteristic = commandCharacteristic
        if (gatt == null || commandCharacteristic == null) {
            println("Error: Cannot write command, device is unavailable.")
            onCommandWriteFailed()
            return
        }

        val value = payload.toByteArray()
        val isStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                commandCharacteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            commandCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            commandCharacteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(commandCharacteristic)
        }

        if (!isStarted) {
            println("Error: Could not start writing command $payload")
            onCommandWriteFailed()
        }
    }

    /**
     * This function writes a value to the client characteristic configuration descriptor of the notification
     * characteristic.
     * @return Whether the write was started.
     * */
    @SuppressLint("MissingPermission")
    private fun writeNotificationDescriptor(value: ByteArray): Boolean {
        val gatt = gatt ?: return false
        val descriptor = notificationCharacteristic?.getDescriptor(cccdUuid) ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    /**
     * This function starts connecting to the Mac unless a connection is already being established. Operations that
     * need the connection resume once it is established.
     * */
    private fun ensureConnection() {
        if (isScanning || bleScanTimer != null || gatt != null) {
            println("Connection to the Mac is already being established.")
            return
        }

        startScan()
    }

    /**
     * This function initialises a new Bluetooth connection with a device running the Cellular app using data from a QR code.
     * @param serviceUUID The BLE service UUID of the device running the Cellular app.
     * @param sharedKey The shared key of the device running the Cellular app.
     * @param initOnConnectCallback The callback to run when the connection is successfully initialised.
     * @param context The context of the application that calls this function.
     * */
    suspend fun initializeFromQR(
        serviceUUID: String,
        sharedKey: String,
        initOnConnectCallback: () -> (Unit),
        context: Context
    ) {
        // Validate service UUID and Key
        val uuidRegex =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        val alphaNumericRegex = Pattern.compile("^[a-zA-Z0-9]*$")
        if (!uuidRegex.matcher(serviceUUID).matches()) {
            println("Invalid service UUID.")
            return
        }
        if (!alphaNumericRegex.matcher(sharedKey).matches()) {
            println("Invalid shared key.")
            return
        }

        // Store service UUID and Key in DataStore
        context.dataStore.edit { settings ->
            settings[serviceUUIDKey] = serviceUUID.lowercase()
            settings[sharedKeyKey] = sharedKey
        }

        isFirstInitializing = true
        this.initOnConnectCallback = initOnConnectCallback
        this.serviceUUID = serviceUUID.lowercase()
        this.sharedKey = sharedKey
        initialize(context)
    }

    /**
     * This function initialises a Bluetooth connection with a device running the Cellular app using stored credentials.
     * @param context The context of the application that calls this function.
     * */
    suspend fun initializeFromDataStore(initOnConnectCallback: () -> (Unit), context: Context) {
        // Retrieve service UUID and Key from DataStore
        serviceUUID = context.dataStore.data.map { settings ->
            settings[serviceUUIDKey] ?: ""
        }.first()
        sharedKey = context.dataStore.data.map { settings ->
            settings[sharedKeyKey] ?: ""
        }.first()
        deviceAddress = context.dataStore.data.map { settings ->
            settings[deviceAddressKey] ?: ""
        }.first()
        isSetupComplete = context.dataStore.data.map { settings ->
            settings[isSetupCompleteKey] ?: false
        }.first()

        if (serviceUUID == "") {
            println("Error: Service UUID not found in DataStore.")
            return
        }

        if (sharedKey == "") {
            println("Error: Shared Key not found in DataStore.")
            return
        }

        if (deviceAddress == "") {
            println("Error: Device Address not found in DataStore.")
            return
        }

        enqueueOperation {
            isInitializing = true
            this.initOnConnectCallback = initOnConnectCallback
            initialize(context)
        }
    }

    /**
     * This function starts the scan for BLE devices when initialising.
     * @param context The context of the application that calls this function.
     * */
    private fun initialize(context: Context) {
        // Initialise context and BluetoothManager
        this.context = context
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        aes = AES(sharedKey!!)
        wifiHotspotManager = WifiHotspotManager(context)
        registerHotspotStateReceiver()

        // Start BLE scan
        startScan()
    }

    /**
     * This function continues the second part of setup - sending the Hello World command to the device.
     * */
    private fun initialize2() {
        // Check for Bluetooth Connect permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                println("Error: The Bluetooth Connect permission has not been granted.")
                return
            }
        }

        if (!isReady()) {
            startScan()
        } else {
            // Write Hello World command to command characteristic
            writeCommand(CommandType.HelloWorld)
        }
    }

    /**
     * This function starts the scan for BLE devices matching the set service UUID. The serviceUUID variable has to be set before calling this function.
     * */
    private fun startScan() {
        // A scan is already scheduled; starting another would leave two timers rescanning forever
        if (bleScanTimer != null) {
            println("Scan already scheduled.")
            return
        }

        // Schedule repeating 29min 30s timer
        bleScanTimer = Timer()
        bleScanTimer!!.schedule(object : TimerTask() {
            override fun run() {
                // Reset BLE connection before starting scan
                resetBLE(stopScanTimer = false)

                // Scan for BLE devices advertising this service UUID
                val filter =
                    ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(serviceUUID)).build()
                val scanSettings = ScanSettings.Builder().apply {
                    if (isFirstInitializing) {
                        setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    } else {
                        setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    }
                }
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                    .build()

                // Start BLE scan
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_SCAN
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        println("Error: The Bluetooth Scan permission has not been granted.")
                        return
                    }
                }

                bluetoothAdapter.bluetoothLeScanner.startScan(
                    listOf(filter),
                    scanSettings,
                    bleScanCallback
                )
                isScanning = true
            }
        }, 0, 1770000)
    }

    /**
     * This function stops the scan for BLE devices if it is running.
     * */
    private fun stopScan(stopScanTimer: Boolean = true) {
        if (isScanning) {
            // Check for Bluetooth Connect permission on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    println("Error: The Bluetooth Scan permission has not been granted.")
                    return
                }
            }

            // Stop BLE scan
            bluetoothAdapter.bluetoothLeScanner.stopScan(bleScanCallback)

            // Set isScanning to false
            isScanning = false
        }

        // Stop BLE scan timer if needed (it may be scheduled even before the first scan starts)
        if (stopScanTimer) {
            bleScanTimer?.cancel()
            bleScanTimer = null
        }
    }

    /**
     * This function checks whether the Mac is connected and its characteristics have been discovered.
     * */
    @SuppressLint("MissingPermission")
    private fun isReady(): Boolean {
        return gatt != null && commandCharacteristic != null && bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            .any { it.address == connectDevice?.address }
    }

    /**
     * This function gets a characteristic reference based on its UUID from the GATT device. The GATT device's services must be discovered before calling this function.
     * @param characteristicUUID The UUID of the characteristic to get.
     * */
    private fun getCharacteristicForDevice(characteristicUUID: String): BluetoothGattCharacteristic? {
        if (gatt == null) {
            println("Error: No GATT device initialised.")
            return null
        }

        with(gatt!!.services.find { it.uuid.toString() == serviceUUID }) {
            if (this == null) {
                println("Error: Cellular service could not be found")
                return null
            }

            return characteristics.find { it.uuid.toString() == characteristicUUID }
        }
    }

    /**
     * This function enables notifications from the Mac through BLE.
     * */
    private fun enableNotifications() {
        if (gatt == null) {
            println("Error: Device is unavailable.")
            return
        }
        if (notificationCharacteristic == null) {
            println("Error: Notification characteristic is unavailable.")
            return
        }

        // Check for Bluetooth Connect permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                println("Error: The Bluetooth Connect permission has not been granted.")
                return
            }
        }

        gatt!!.setCharacteristicNotification(notificationCharacteristic!!, true)
        if (!writeNotificationDescriptor(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
            // Without the subscription the Mac never considers this device connected, so start over
            println("Error: Could not subscribe to notifications, reconnecting.")
            disconnect()
            resetBLE()
            startScan()
        }
    }

    /**
     * This function shares the hotspot details with the device running the Cellular app. This is a public function to enqueue this operation.
     * @param ssid The SSID of the hotspot to connect to.
     * @param password The password of the hotspot to connect to.
     * @param onHotspotDetailsSharedCallback The callback function to be called when the hotspot details have been shared.
     * */
    fun shareHotspotDetails(
        ssid: String,
        password: String,
        onHotspotDetailsSharedCallback: () -> Unit
    ) {
        // Check for Bluetooth Connect permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                println("Error: The Bluetooth Connect permission has not been granted.")
                return
            }
        }

        // Enqueue operation to share hotspot details
        enqueueOperation {
            startShareHotspotDetails(ssid, password, onHotspotDetailsSharedCallback)
        }
    }

    /**
     * This function shares the hotspot details with the Mac.
     * @param ssid The SSID of the hotspot to connect to.
     * @param password The password of the hotspot to connect to.
     * @param onHotspotDetailsSharedCallback The callback function to be called when the hotspot details have been shared.
     * */
    @SuppressLint("MissingPermission")
    private fun startShareHotspotDetails(
        ssid: String,
        password: String,
        onHotspotDetailsSharedCallback: () -> Unit
    ) {
        isSharingHotspotDetails = true
        this.onHotspotDetailsSharedCallback = onHotspotDetailsSharedCallback

        shareHotspotDetails2 = {
            val (cipherText, iv) = aes.encrypt("\"$ssid\" \"$password\"")
            writeCommand("${CommandType.ShareHotspotDetails} $iv $cipherText")
        }

        hotspotDetailsRetryCallback = {
            resetBLE()
            startShareHotspotDetails(ssid, password, onHotspotDetailsSharedCallback)
        }

        if (!isReady()) {
            ensureConnection()
        } else {
            shareHotspotDetails2!!.invoke()
        }
    }

    /**
     * This function marks setup as complete (stored in DataStore).
     * */
    suspend fun markSetupComplete() {
        isSetupComplete = true
        context.dataStore.edit { settings ->
            settings[isSetupCompleteKey] = true
        }
    }

    /**
     * This function shares the phone info with the Mac. Pass -1 to any of the parameters to indicate that the value is not present. This is a public function to enqueue this operation.
     * @param signalLevel The signal level of the phone (-1 to 3)
     * @param networkType The network type of the phone (-1, GPRS, E, 3G, 4G, 5G)
     * @param batteryPercentage The battery percentage of the phone (-1 to 100)
     */
    fun sharePhoneInfo(signalLevel: Int, networkType: String, batteryPercentage: Int) {
        // Check for Bluetooth Connect permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                println("Error: The Bluetooth Connect permission has not been granted.")
                return
            }
        }

        // Enqueue operation to share phone info
        enqueueOperation {
            startSharePhoneInfo(signalLevel, networkType, batteryPercentage)
        }
    }

    /**
     * This function shares the phone info with the Mac. Pass -1 to any of the parameters to indicate that the value is not present.
     * @param signalLevel The signal level of the phone (-1 to 3)
     * @param networkType The network type of the phone (-1, GPRS, E, 3G, 4G, 5G)
     * @param batteryPercentage The battery percentage of the phone (-1 to 100)
     */
    @SuppressLint("MissingPermission")
    private fun startSharePhoneInfo(signalLevel: Int, networkType: String, batteryPercentage: Int) {
        isSharingPhoneInfo = true

        sharePhoneInfo2 = {
            writeCommand("${CommandType.SharePhoneInfo} $signalLevel $networkType $batteryPercentage")
        }

        if (!isReady()) {
            ensureConnection()
        } else {
            sharePhoneInfo2!!.invoke()
        }
    }

    /**
     * This function enables the hotspot on the phone.
     * */
    fun enableHotspot() {
        // Indicate that hotspot is connecting
        _connectStatus.value = ConnectStatus.Connecting

        if (wifiHotspotManager.isTetherActive) {
            onTetheringStarted()
        } else if (!wifiHotspotManager.startTethering(myHotspotOnStartTetheringCallback)) {
            requestHotspotFromUser()
        }
    }

    /**
     * This function asks the user to turn on the hotspot, for when this app isn't allowed to (Android 16+). The Mac is
     * told to connect as soon as the hotspot turns on, however the user turns it on.
     * */
    private fun requestHotspotFromUser() {
        println("Asking the user to turn on the hotspot")
        _isAwaitingUserHotspot.value = true

        // Give up eventually so that neither device waits forever
        awaitingUserHotspotTimer?.cancel()
        awaitingUserHotspotTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (_isAwaitingUserHotspot.value) {
                        println("The hotspot was not turned on in time")
                        disableHotspot()
                    }
                }
            }, AWAITING_USER_HOTSPOT_TIMEOUT_MS)
        }

        showHotspotRequestNotification()
    }

    /**
     * This function stops waiting for the user to turn on the hotspot.
     * */
    private fun stopAwaitingUserHotspot() {
        _isAwaitingUserHotspot.value = false
        awaitingUserHotspotTimer?.cancel()
        awaitingUserHotspotTimer = null
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(HOTSPOT_REQUEST_NOTIFICATION_ID)
    }

    /**
     * This function shows a notification asking the user to turn on the hotspot.
     * */
    private fun showHotspotRequestNotification() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            println("Error: The Post Notifications permission has not been granted.")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            HOTSPOT_REQUEST_CHANNEL_ID,
            "Hotspot requests",
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "Asks you to turn on your hotspot when your Mac needs it."
        notificationManager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            context, 0, hotspotSettingsIntent(), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, HOTSPOT_REQUEST_CHANNEL_ID)
            .setContentTitle("Turn on your hotspot")
            .setContentText("Your Mac wants to connect. Tap to open hotspot settings.")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        notificationManager.notify(HOTSPOT_REQUEST_NOTIFICATION_ID, notification)
    }

    /**
     * This receiver follows the hotspot being turned on or off outside this app, e.g. from Quick Settings.
     * */
    private val hotspotStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)) {
                WIFI_AP_STATE_ENABLED -> if (_isAwaitingUserHotspot.value) {
                    println("The user turned on the hotspot")
                    stopAwaitingUserHotspot()
                    onTetheringStarted()
                }

                WIFI_AP_STATE_DISABLED -> if (
                    _connectStatus.value == ConnectStatus.Connecting && !_isAwaitingUserHotspot.value ||
                    _connectStatus.value == ConnectStatus.Connected
                ) {
                    // Let the Mac know right away instead of waiting for it to notice the network disappear
                    println("The hotspot was turned off outside Cellular")
                    disableHotspot()
                }
            }
        }
    }

    /**
     * This function starts following the hotspot's state.
     * */
    private fun registerHotspotStateReceiver() {
        if (isHotspotStateReceiverRegistered) return

        ContextCompat.registerReceiver(
            context.applicationContext,
            hotspotStateReceiver,
            IntentFilter(ACTION_WIFI_AP_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        isHotspotStateReceiverRegistered = true
    }

    /**
     * This function disables the hotspot on the phone.
     * @param indicateOnly Whether this device should only update itself that the hotspot has been disabled.
     * @param isDisconnecting Whether the hotspot is being disabled due to this device disconnecting from the Mac.
     * */
    fun disableHotspot(indicateOnly: Boolean = false, isDisconnecting: Boolean = false) {
        stopAwaitingUserHotspot()

        if (!isDisconnecting) {
            // Indicate that hotspot is disconnected
            _connectStatus.value = ConnectStatus.Idle
        }

        // Disconnect from hotspot
        if (wifiHotspotManager.isHotspotStartedByUs && wifiHotspotManager.isTetherActive) {
            wifiHotspotManager.stopTethering()
        }

        // Indicate to Mac to disconnect from hotspot if needed
        if (!indicateOnly) {
            // Check for Bluetooth Connect permission on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    println("Error: The Bluetooth Connect permission has not been granted.")
                    return
                }
            }

            enqueueOperation {
                isDisconnectingFromHotspot = true

                disconnectHotspot2 = {
                    writeCommand(CommandType.DisconnectFromHotspot)
                }

                if (!isReady()) {
                    ensureConnection()
                } else {
                    disconnectHotspot2!!.invoke()
                }
            }
        }
    }

    /**
     * This function updates this device that the Mac is connected to its hotspot.
     * */
    fun indicateConnectedHotspot() {
        _connectStatus.value = ConnectStatus.Connected
    }

    /**
     * This function registers callbacks from a view model for error handling.
     * @param onScanFailedCallback The callback function to be called when BLE scans fail.
     * @param onUnexpectedErrorCallback The callback function to be called when an unexpected error occurs.
     * @param onHotspotDetailsShareFailedCallback The callback function to be called when sharing hotspot details fails.
     * @param onConnectFailedCallback The callback function to be called when connecting to the Mac fails.
     * @param onBondFailedCallback The callback function to be called when bonding fails.
     * @param onResetFailedCallback The callback function to be called when unlinking from the Mac fails.
     * */
    fun registerForErrorHandling(
        onScanFailedCallback: (() -> Unit) -> Unit,
        onUnexpectedErrorCallback: (() -> Unit) -> Unit,
        onHotspotDetailsShareFailedCallback: (() -> Unit) -> Unit,
        onConnectFailedCallback: (() -> Unit) -> Unit,
        onBondFailedCallback: (() -> Unit) -> Unit,
        onResetFailedCallback: (() -> Unit) -> Unit
    ) {
        this.onScanFailedCallback = onScanFailedCallback
        this.onUnexpectedErrorCallback = onUnexpectedErrorCallback
        this.onConnectFailedCallback = onConnectFailedCallback
        this.onBondFailedCallback = onBondFailedCallback
        this.onHotspotDetailsShareFailedCallback = onHotspotDetailsShareFailedCallback
        this.onResetFailedCallback = onResetFailedCallback
    }

    /**
     * This function resets the BLE connection to allow reconnecting to a BLE device.
     * */
    @SuppressLint("MissingPermission")
    private fun resetBLE(stopScanTimer: Boolean = true) {
        stopScan(stopScanTimer = stopScanTimer)

        // Release the GATT client. Android only has a limited number of them, so leaking one per reconnect eventually
        // makes every connection attempt fail.
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            println("Error: Could not close GATT client: ${e.message}")
        }

        connectDevice = null
        gatt = null
        commandCharacteristic = null
        notificationCharacteristic = null
        isScanning = false
        isConnecting = false
        isDisconnecting = false
        connectionRetryCount = 0
        hotspotShareRetryCount = 0
    }

    /**
     * This function registers a callback from a view model for when unlinking from the Mac is complete.
     * @param onResetCompleteCallback The callback function to be called when unlinking from the Mac is complete.
     * */
    fun registerForReset(onResetCompleteCallback: () -> Unit) {
        this.onResetCompleteCallback = onResetCompleteCallback
    }

    /**
     * This function unlinks this device from the Mac.
     * @param indicateOnly Whether this device should only update itself that it is being unlinked.
     */
    fun reset(indicateOnly: Boolean = false) {
        // Check for Bluetooth Connect permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                println("Error: The Bluetooth Connect permission has not been granted.")
                return
            }
        }

        if (indicateOnly || gatt == null || commandCharacteristic == null) {
            // Skip notifying the Mac that reset has started if the Mac is not connected
            isIndicatingReset = true
            reset2()
        } else {
            // Notify the Mac that reset has started. The flag is only set once this operation runs, so that an
            // operation still in flight doesn't mistake its own completion for the reset's.
            enqueueOperation {
                isIndicatingReset = true
                indicateReset = {
                    writeCommand(CommandType.IndicateReset)
                }
                indicateReset!!.invoke()
            }
        }
    }

    /**
     * This function disconnects from the Mac (Bluetooth).
     * */
    fun disconnect() {
        // Check for Bluetooth Connect permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                println("Error: The Bluetooth Connect permission has not been granted.")
                return
            }
        }

        if (gatt == null) {
            println("Error: Device not connected.")
            return
        }

        isDisconnecting = true
        gatt!!.disconnect()
        onDisconnected(noRestartScan = true)
    }

    /**
     * This function completes the unlinking from the Mac.
     * */
    @SuppressLint("MissingPermission")
    private fun reset2() {
        // Disable notifications
        if (gatt != null && notificationCharacteristic != null) {
            gatt!!.setCharacteristicNotification(notificationCharacteristic!!, false)
            writeNotificationDescriptor(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }

        // Un-bond from device
        val bondedDevices = bluetoothAdapter.bondedDevices
        for (bondedDevice in bondedDevices) {
            if (bondedDevice.address == deviceAddress) {
                try {
                    bondedDevice::class.java.getMethod("removeBond").invoke(bondedDevice)
                } catch (e: Exception) {
                    println("Failed to un-bond from device: ${e.message}")
                }
                break
            }
        }

        // Reset all variables
        serviceUUID = null
        sharedKey = null
        deviceAddress = null
        isFirstInitializing = false
        isInitializing = false
        initOnConnectCallback = null
        isSharingHotspotDetails = false
        shareHotspotDetails2 = null
        onHotspotDetailsSharedCallback = null
        isSharingPhoneInfo = false
        sharePhoneInfo2 = null
        isConnectingToHotspot = false
        connectHotspot2 = null
        isDisconnectingFromHotspot = false
        disconnectHotspot2 = null
        isIndicatingReset = false
        indicateReset = null
        hotspotDetailsRetryCallback = null
        isSetupComplete = false
        _connectStatus.value = ConnectStatus.Disconnected
        _isSeePhoneInfoEnabled.value = false

        // This part is run 1.5s later to allow the Mac to receive and process the reset command
        Timer().schedule(object : TimerTask() {
            override fun run() {
                // Disconnect from device and close the GATT client
                resetBLE()

                // Let view model know that reset is complete
                onResetCompleteCallback!!.invoke()
            }
        }, 1500)
    }
}