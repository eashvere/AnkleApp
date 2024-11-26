package com.example.ankleapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import com.example.ankleapp.data.AnkleDataRepository
import com.example.ankleapp.ui.theme.AnkleAppTheme
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity(), WalkingListener, BleManager.ConnectionListener {

    private lateinit var bleManager: BleManager
    private lateinit var walkingDetector: WalkingDetector
    private lateinit var dataRepository: AnkleDataRepository

    private var isWalking by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("User is not walking")
    private var receivedData by mutableStateOf("")
    private var dailyStats by mutableStateOf("No stats available")

    @SuppressLint("BatteryLife", "ObsoleteSdkInt")
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

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

        // Request battery optimization exemption
        requestBatteryOptimizationExemption()

        dataRepository = AnkleDataRepository(this)
        bleManager = BleManager(this).apply {
            connectionListener = this@MainActivity
        }

        checkPermissionsAndStart()

        // Start the service
        val serviceIntent = Intent(this, WalkingDetectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Start periodic stats updates
        updateDailyStats()

        setContent {
            AnkleAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainContent(
                        isWalking = isWalking,
                        connectionStatus = connectionStatus,
                        receivedData = receivedData,
                        dailyStats = dailyStats
                    )
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun updateDailyStats() {
        val stats = dataRepository.getDailyStats(LocalDateTime.now())
        val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy")

        dailyStats = """
        Date: ${stats.date.format(formatter)}
        
        Walking Summary:
        • Total Walking Time: ${formatDuration(stats.totalWalkingTime)}
        • Bad Posture Time: ${formatDuration(stats.totalBadPostureTime)}
        • Bad Posture Percentage: ${String.format("%.1f", stats.badPosturePercentage())}%
        
        Events Summary:
        • Total Events: ${stats.totalEvents()}
        • Avg Event Duration: ${String.format("%.1f", stats.averageEventDuration)} seconds
    """.trimIndent()
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, remainingSeconds)
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            // Add notification permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else null
        ).filterNotNull().toTypedArray()

        if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
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
            bleManager.scanForSpecificDevice("SmartAnkleBrace")
            dataRepository.startWalkingSession()
        } else {
            bleManager.disconnectGatt()
            connectionStatus = "User is not walking"
            dataRepository.currentSessionId?.let {
                dataRepository.endWalkingSession(it)
                updateDailyStats() // Update stats when walking session ends
            }
        }
    }

    override fun onConnected() {
        connectionStatus = "Connection established to brace"
    }

    override fun onDisconnected() {
        if (isWalking) {
            connectionStatus = "Trying to connect"
        } else {
            connectionStatus = "User is not walking"
        }
    }

    override fun onConnectionStopped() {
        connectionStatus = "User is not walking"
    }

    override fun onDataReceived(data: String) {
        receivedData = data
        dataRepository.handlePostureData(data)
    }

    override fun onPause() {
        super.onPause()
        walkingDetector.stopDetection()
        bleManager.stopScanning()
    }

    override fun onResume() {
        super.onResume()
        walkingDetector.startDetection()
        updateDailyStats() // Update stats when app resumes
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanup()
        walkingDetector.stopDetection()
    }
}

@Composable
fun MainContent(
    isWalking: Boolean,
    connectionStatus: String,
    receivedData: String,
    dailyStats: String
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Walking Status
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isWalking) "User is walking" else "User is not walking",
                        fontSize = 20.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = connectionStatus,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            // Data Display
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Current Data",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = receivedData,
                        fontSize = 16.sp
                    )
                }
            }

            // Daily Stats
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Daily Statistics",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = dailyStats,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainContentPreview() {
    AnkleAppTheme {
        MainContent(
            isWalking = false,
            connectionStatus = "User is not walking",
            receivedData = "",
            dailyStats = "No stats available"
        )
    }
}