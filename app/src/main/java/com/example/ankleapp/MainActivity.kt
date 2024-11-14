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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ankleapp.ui.theme.AnkleAppTheme

class MainActivity : ComponentActivity(), WalkingListener, BleManager.ConnectionListener {

    private lateinit var bleManager: BleManager
    private lateinit var walkingDetector: WalkingDetector

    private var isWalking by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("User is not walking")
    private var receivedData by mutableStateOf("")

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

        bleManager = BleManager(this).apply {
            connectionListener = this@MainActivity
        }

        checkPermissionsAndStart()

        setContent {
            AnkleAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainContent(isWalking, connectionStatus, receivedData)
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
            connectionStatus = "Trying to connect"
            startBleScan()
        } else {
            stopBleScan()
            connectionStatus = "User is not walking"
        }
    }

    private fun startBleScan() {
        bleManager.scanForSpecificDevice("SmartAnkleBrace") // Replace with the desired device name or UUID
    }

    private fun stopBleScan() {
        bleManager.stopScanning()
    }

    // Connection listener methods
    override fun onConnected() {
        connectionStatus = "Connection established to brace"
    }

    override fun onDisconnected() {
        connectionStatus = "Trying to connect"
        receivedData = "" // Clear the data when disconnected
    }

    override fun onDataReceived(data: String) {
        receivedData = data // Update the UI with received data
    }

    override fun onPause() {
        super.onPause()
        walkingDetector.stopDetection()
        stopBleScan()
    }

    override fun onResume() {
        super.onResume()
        walkingDetector.startDetection()
    }
}

@Composable
fun MainContent(isWalking: Boolean, connectionStatus: String, receivedData: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isWalking) "User is walking" else "User is not walking",
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = connectionStatus,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Received Data: $receivedData",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainContentPreview() {
    AnkleAppTheme {
        MainContent(isWalking = false, connectionStatus = "User is not walking", receivedData = "")
    }
}
