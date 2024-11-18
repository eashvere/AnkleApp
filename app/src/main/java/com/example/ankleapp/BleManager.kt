package com.example.ankleapp

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

class BleManager(private val context: Context) {

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onDataReceived(data: String)
        fun onConnectionStopped()
    }

    var connectionListener: ConnectionListener? = null
    private var lastConnectedDeviceAddress: String? = null
    private var isIntentionalDisconnect = false

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothGatt: BluetoothGatt? = null

    private val SCAN_PERIOD: Long = 10000
    private val serviceUUID = UUID.fromString("23e6ab8d-4bf2-4be6-b3e0-9d4c07028e5e")
    private val characteristicUUID = UUID.fromString("bba3b2e6-5dbf-406b-ad70-072c8f46b5cf")

    private val scanFilters = listOf(
        ScanFilter.Builder()
            .setDeviceName("SmartAnkleBrace")
            .build()
    )

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .build()

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device

            if (device.name == "SmartAnkleBrace") {
                Log.d("BleManager", "Found device: ${device.address}")
                stopScanning()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleManager", "Scan failed with error: $errorCode")
            scanning = false
            resetConnection()
        }
    }

    @SuppressLint("MissingPermission")
    fun scanForSpecificDevice(deviceName: String) {
        isIntentionalDisconnect = false
        resetConnection()
        startScanning()
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!scanning && bluetoothAdapter?.isEnabled == true) {
            scanning = true

            handler.postDelayed({
                stopScanning()
            }, SCAN_PERIOD)

            try {
                bluetoothLeScanner?.startScan(scanFilters, scanSettings, leScanCallback)
                Log.d("BleManager", "Scanning started with filters")
            } catch (e: Exception) {
                Log.e("BleManager", "Failed to start scan: ${e.message}")
                scanning = false
            }
        } else {
            Log.e("BleManager", "Cannot start scan. Bluetooth enabled: ${bluetoothAdapter?.isEnabled}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (scanning) {
            scanning = false
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
                Log.d("BleManager", "Scanning stopped")
            } catch (e: Exception) {
                Log.e("BleManager", "Error stopping scan: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d("BleManager", "Attempting to connect to device: ${device.address}")

        lastConnectedDeviceAddress = device.address

        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null

        handler.postDelayed({
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }, 200)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("BleManager", "Connection state change: status=$status newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i("BleManager", "Connected to GATT server.")
                    handler.post {
                        gatt.discoverServices()
                        connectionListener?.onConnected()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i("BleManager", "Disconnected from GATT server.")
                    resetConnection()
                    handler.post {
                        if (isIntentionalDisconnect) {
                            connectionListener?.onConnectionStopped()
                        } else {
                            connectionListener?.onDisconnected()
                        }
                    }
                }
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                resetConnection()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val characteristic = gatt.getService(serviceUUID)?.getCharacteristic(characteristicUUID)
                if (characteristic != null) {
                    enableNotifications(characteristic)
                }
            }
        }

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

    @SuppressLint("MissingPermission")
    private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        bluetoothGatt?.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        bluetoothGatt?.writeDescriptor(descriptor)
    }

    @SuppressLint("MissingPermission")
    fun disconnectGatt() {
        isIntentionalDisconnect = true
        resetConnection()
        connectionListener?.onConnectionStopped()
    }

    @SuppressLint("MissingPermission")
    private fun resetConnection() {
        stopScanning()

        bluetoothGatt?.let { gatt ->
            try {
                gatt.disconnect()
                handler.postDelayed({
                    gatt.close()
                    bluetoothGatt = null
                }, 500)
            } catch (e: Exception) {
                Log.e("BleManager", "Error during GATT cleanup: ${e.message}")
            }
        }

        Log.d("BleManager", "Connection reset completed")
    }

    fun cleanup() {
        isIntentionalDisconnect = true
        handler.removeCallbacksAndMessages(null)
        resetConnection()
        lastConnectedDeviceAddress = null
    }
}