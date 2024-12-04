package com.example.ankleapp

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.Charset


class BleManager(private val context: Context) {

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onDataReceived(data: String)
        fun onConnectionStopped()
    }

    var connectionListener: ConnectionListener? = null
    // Connection state tracking
    private var isIntentionalDisconnect = false
    private var shouldMaintainConnection = false
    private var lastConnectedDeviceAddress: String? = null


    // Bluetooth components
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null

    // Connection management
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Constants
    private val SCAN_PERIOD = 10000L          // Scan timeout: 10 seconds
    private val RECONNECTION_DELAY = 5000L    // Wait between reconnection attempts: 5 seconds
    private val GATT_RETRY_DELAY = 200L       // Delay before GATT operations: 200ms
    private val MAX_RETRY_ATTEMPTS = 3        // Maximum connection retry attempts
    private var currentRetryAttempt = 0

    // Device identifiers
    private val DEVICE_NAME = "SmartAnkleBrace"
    private val serviceUUID = UUID.fromString("23e6ab8d-4bf2-4be6-b3e0-9d4c07028e5e")
    private val characteristicUUID = UUID.fromString("bba3b2e6-5dbf-406b-ad70-072c8f46b5cf")

    // Scan settings
    private val scanFilters = listOf(
        ScanFilter.Builder()
            .setDeviceName(DEVICE_NAME)
            .build()
    )


    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .build()

    @SuppressLint("MissingPermission")
    fun startContinuousConnection() {
        shouldMaintainConnection = true
        isIntentionalDisconnect = false
        currentRetryAttempt = 0

        // Start initial connection attempt
        startScanning()

        // Setup reconnection monitoring
        reconnectionScope.launch {
            while (shouldMaintainConnection) {
                if (bluetoothGatt == null && !scanning) {
                    Log.d("BleManager", "Connection lost, attempting reconnection...")
                    startScanning()
                }
                delay(RECONNECTION_DELAY)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!scanning && bluetoothAdapter?.isEnabled == true) {
            scanning = true
            currentRetryAttempt++

            // Set scan timeout
            handler.postDelayed({
                stopScanning()
                if (bluetoothGatt == null && shouldMaintainConnection) {
                    handleScanTimeout()
                }
            }, SCAN_PERIOD)

            try {
                bluetoothLeScanner?.startScan(scanFilters, scanSettings, leScanCallback)
                Log.d("BleManager", "Started scanning for $DEVICE_NAME (Attempt $currentRetryAttempt)")
            } catch (e: Exception) {
                Log.e("BleManager", "Scan failed: ${e.message}")
                scanning = false
                handleScanError(e)
            }
        }
    }


    private fun handleScanTimeout() {
        if (currentRetryAttempt < MAX_RETRY_ATTEMPTS) {
            Log.d("BleManager", "Scan timeout, retrying...")
            handler.postDelayed({ startScanning() }, RECONNECTION_DELAY)
        } else {
            Log.e("BleManager", "Max retry attempts reached")
            currentRetryAttempt = 0
        }
    }

    private fun handleScanError(error: Exception) {
        if (currentRetryAttempt < MAX_RETRY_ATTEMPTS) {
            Log.d("BleManager", "Scan error, retrying in ${RECONNECTION_DELAY}ms")
            handler.postDelayed({ startScanning() }, RECONNECTION_DELAY)
        } else {
            Log.e("BleManager", "Max retry attempts reached after error")
            currentRetryAttempt = 0
        }
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == DEVICE_NAME) {
                Log.d("BleManager", "Found device: ${result.device.address}")
                stopScanning()
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleManager", "Scan failed with error code: $errorCode")
            scanning = false
            handleScanError(Exception("Scan failed with error code: $errorCode"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d("BleManager", "Attempting to connect to ${device.address}")

        lastConnectedDeviceAddress = device.address

        // Don't disconnect if we're already connected to this device
        if (bluetoothGatt?.device?.address == device.address) {
            Log.d("BleManager", "Already connected to this device")
            return
        }

        // Clean up existing connection
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null

        // Delay before new connection to ensure cleanup
        handler.postDelayed({
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            ).apply {
                requestMtu(512)  // Request larger MTU for better data transfer
            }
        }, GATT_RETRY_DELAY)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d("BleManager", "Connected to GATT server")
                    currentRetryAttempt = 0  // Reset retry counter on successful connection
                    handler.post {
                        gatt.discoverServices()
                        connectionListener?.onConnected()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d("BleManager", "Disconnected from GATT server")
                    bluetoothGatt = null
                    handler.post {
                        connectionListener?.onDisconnected()

                        // Attempt reconnection if not intentionally disconnected
                        if (shouldMaintainConnection && !isIntentionalDisconnect) {
                            startScanning()
                        }
                    }
                }
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("BleManager", "GATT operation failed with status: $status")
                handleGattError(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val characteristic = gatt
                    .getService(serviceUUID)
                    ?.getCharacteristic(characteristicUUID)

                if (characteristic != null) {
                    enableNotifications(characteristic)
                } else {
                    Log.e("BleManager", "Required characteristic not found")
                    handleGattError(BluetoothGatt.GATT_FAILURE)
                }
            } else {
                Log.e("BleManager", "Service discovery failed")
                handleGattError(status)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BleManager", "Characteristic write successful")
            } else {
                Log.e("BleManager", "Characteristic write failed: $status")
                handleGattError(status)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.getStringValue(0)
            handler.post {
                connectionListener?.onDataReceived(data)
            }
        }
    }



    private fun handleGattError(status: Int) {
        Log.e("BleManager", "GATT error occurred: $status")
        if (currentRetryAttempt < MAX_RETRY_ATTEMPTS && shouldMaintainConnection) {
            currentRetryAttempt++
            handler.postDelayed({
                // Retry connection
                lastConnectedDeviceAddress?.let { address ->
                    bluetoothAdapter?.getRemoteDevice(address)?.let { device ->
                        connectToDevice(device)
                    }
                }
            }, RECONNECTION_DELAY)
        }
    }




    @SuppressLint("MissingPermission")
    private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        try {
            val gatt = bluetoothGatt ?: return

            // First, enable notifications at the GATT level
            gatt.setCharacteristicNotification(characteristic, true)

            // Get the Client Characteristic Configuration Descriptor
            val descriptor = characteristic.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")  // Standard UUID for notifications
            )

            if (descriptor == null) {
                Log.e("BleManager", "Notification descriptor not found")
                return
            }

            // Write the descriptor value in a specific sequence
            handler.post {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                Log.d("BleManager", "Writing notification descriptor")
            }

        } catch (e: Exception) {
            Log.e("BleManager", "Error enabling notifications: ${e.message}")
        }
    }

    fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d("BleManager", "Notifications successfully enabled")
        } else {
            Log.e("BleManager", "Failed to enable notifications: $status")
        }
    }



    @SuppressLint("MissingPermission")
    fun stopContinuousConnection() {
        shouldMaintainConnection = false
        isIntentionalDisconnect = true
        cleanup()
        connectionListener?.onConnectionStopped()
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        shouldMaintainConnection = false
        reconnectionScope.cancel()
        stopScanning()

        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                handler.postDelayed({
                    gatt.close()
                    bluetoothGatt = null
                }, 500)
            } catch (e: Exception) {
                Log.e("BleManager", "Error during cleanup: ${e.message}")
            }
        }

        handler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (scanning) {
            scanning = false
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
                Log.d("BleManager", "Stopped scanning")
            } catch (e: Exception) {
                Log.e("BleManager", "Error stopping scan: ${e.message}")
            }
        }
    }


}
