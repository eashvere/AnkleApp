package com.example.ankleapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ankleapp.ui.theme.AnkleAppTheme

class MainActivity : ComponentActivity(), WalkingListener {

    private lateinit var bleManager: BleManager
    private lateinit var walkingDetector: WalkingDetector

    private var isWalking by mutableStateOf(false)
    private var devices by mutableStateOf(listOf<BluetoothDevice>())

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startWalkingDetection()
        } else {
            Toast.makeText(this, "Permissions are required for Bluetooth scanning", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = BleManager(this)

        checkPermissionsAndStart()

        setContent {
            AnkleAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainContent(isWalking, devices)
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionsLauncher.launch(permissions)
        } else {
            startWalkingDetection()
        }
    }

    private fun startWalkingDetection() {
        walkingDetector = WalkingDetector(this, this)
        walkingDetector.startDetection()

        val serviceIntent = Intent(this, WalkingDetectionService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onWalkingDetected(isWalking: Boolean) {
        this.isWalking = isWalking
        if (isWalking) {
            startBleScan() // Trigger scan when walking is detected
        }
    }

    private fun startBleScan() {
        bleManager.scanLeDevice { updatedDevices ->
            if (isWalking) {
                devices = updatedDevices.toList() // Update device list only if user is walking
            }
        }
    }

    private fun stopBleScan() {
        bleManager.stopScanning()  // Stop scanning and clear the device list
        devices = emptyList()
    }

    override fun onPause() {
        super.onPause()
        walkingDetector.stopDetection()
        stopBleScan()  // Ensure scanning stops when the app is paused
    }

    override fun onResume() {
        super.onResume()
        walkingDetector.startDetection()
    }
}

@Composable
fun MainContent(isWalking: Boolean, devices: List<BluetoothDevice>) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        if (isWalking) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "User is walking", fontSize = 20.sp, modifier = Modifier.padding(bottom = 16.dp))

                LazyColumn {
                    items(devices) { device ->
                        DeviceItem(device)
                    }
                }
            }
        } else {
            Text(text = "User is not walking", fontSize = 20.sp)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceItem(device: BluetoothDevice) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text(
            text = device.name ?: "Unknown Device",
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = device.address,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainContentPreview() {
    AnkleAppTheme {
        MainContent(isWalking = false, devices = emptyList())
    }
}
