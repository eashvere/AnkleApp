package com.example.ankleapp

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast
import java.util.UUID

class BleManager(private val context: Context) {

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onDataReceived(data: String) // Callback for received data
    }

    var connectionListener: ConnectionListener? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    private var scanning = false
    private val handler = Handler()
    private var bluetoothGatt: BluetoothGatt? = null

    private val SCAN_PERIOD: Long = 2000 // 2 seconds for scanning
    private val serviceUUID = java.util.UUID.fromString("23e6ab8d-4bf2-4be6-b3e0-9d4c07028e5e") // Replace with your service UUID
    private val characteristicUUID = java.util.UUID.fromString("bba3b2e6-5dbf-406b-ad70-072c8f46b5cf") // Replace with your characteristic UUID

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (result.device.name == "SmartAnkleBrace") { // Match the device name
                stopScanning()
                connectToDevice(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleManager", "Scan failed with error: $errorCode")
            Toast.makeText(context, "Scan failed with error: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    fun scanForSpecificDevice(deviceName: String) {
        if (!scanning) {
            handler.postDelayed({
                stopScanning()
            }, SCAN_PERIOD)

            scanning = true
            bluetoothLeScanner?.startScan(leScanCallback)
            Log.d("BleManager", "Scanning started.")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (scanning) {
            scanning = false
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.d("BleManager", "Scanning stopped.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i("BleManager", "Connected to GATT server.")
                    bluetoothGatt?.discoverServices()
                    connectionListener?.onConnected()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i("BleManager", "Disconnected from GATT server.")
                    connectionListener?.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BleManager", "Services discovered.")
                val characteristic = gatt.getService(serviceUUID)?.getCharacteristic(characteristicUUID)
                if (characteristic != null) {
                    enableNotifications(characteristic)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.getStringValue(0)
            Log.i("BleManager", "Data received: $data")
            connectionListener?.onDataReceived(data)
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
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectionListener?.onDisconnected()
    }
}
