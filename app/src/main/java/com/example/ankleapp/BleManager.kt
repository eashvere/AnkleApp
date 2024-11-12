package com.example.ankleapp

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast

class BleManager(private val context: Context) {

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
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
                Log.i("BleManager", bluetoothGatt?.services.toString())
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectionListener?.onDisconnected()
    }
}
