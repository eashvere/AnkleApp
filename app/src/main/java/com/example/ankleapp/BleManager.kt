package com.example.ankleapp

import LeDeviceListAdapter
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.util.Log
import android.widget.Toast

class BleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    private var scanning = false
    private val handler = Handler()

    private val SCAN_PERIOD: Long = 2000 // 2 seconds

    private val devicesFound = mutableListOf<BluetoothDevice>()

    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            result.device?.let {
                if (!devicesFound.contains(it)) {
                    devicesFound.add(it)
                    onDevicesUpdated?.invoke(devicesFound) // Update UI with each new device
                    Log.d("BleManager", "Device found: ${it.name} - ${it.address}")
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleManager", "Scan failed with error: $errorCode")
            Toast.makeText(context, "Scan failed with error: $errorCode", Toast.LENGTH_SHORT).show()
        }
    }

    private var onDevicesUpdated: ((List<BluetoothDevice>) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun scanLeDevice(onDevicesUpdated: (List<BluetoothDevice>) -> Unit) {
        this.onDevicesUpdated = onDevicesUpdated

        if (!scanning) {
            devicesFound.clear()
            handler.postDelayed({
                stopScanning()
            }, SCAN_PERIOD)

            scanning = true
            bluetoothLeScanner?.startScan(leScanCallback)
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

    //GATT Connection

    //RX Data

    //Process Data

    //Alert + Dashboard
}
