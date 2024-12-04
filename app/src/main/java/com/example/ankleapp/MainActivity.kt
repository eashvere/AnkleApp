package com.example.ankleapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Bundle
import android.text.format.DateUtils.formatDateTime
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.util.TimeUtils.formatDuration
import androidx.lifecycle.lifecycleScope
import com.example.ankleapp.data.AnkleDataRepository
import com.example.ankleapp.data.AnkleDbHelper
import com.example.ankleapp.ui.theme.AnkleAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale


@SuppressLint("NewApi")
class MainActivity : ComponentActivity(), WalkingListener, BleManager.ConnectionListener,
    AnkleDataRepository.StatsUpdateListener {

    // Core components
    private lateinit var bleManager: BleManager
    private lateinit var walkingDetector: WalkingDetector
    private lateinit var dataRepository: AnkleDataRepository

    // State management
    private var isWalking by mutableStateOf(false)
    private var stepCount by mutableStateOf(0)
    private var connectionStatus by mutableStateOf("Disconnected")
    private var receivedData by mutableStateOf("")
    private var statsState = mutableStateOf<AnkleDataRepository.DailyStats?>(null)
    private var errorMessage by mutableStateOf<String?>(null)

    // Stats management
    private var dailyStats: AnkleDataRepository.DailyStats?
        get() = statsState.value
        set(value) { statsState.value = value }
    private var lastUpdateTime by mutableStateOf(LocalDateTime.now())

    private var weeklyData by mutableStateOf<AnkleDataRepository.WeeklyChartData?>(null)
    private var monthlyData by mutableStateOf<AnkleDataRepository.MonthlyChartData?>(null)

    // Permission handling
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startMonitoring()
        } else {
            showError("Required permissions not granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeCoreComponents()
        setupUserInterface()
        startPeriodicStatsUpdates()
    }

    private fun initializeCoreComponents() {
        dataRepository = AnkleDataRepository(this).apply {
            statsUpdateListener = this@MainActivity
        }

        bleManager = BleManager(this).apply {
            connectionListener = this@MainActivity
        }

        walkingDetector = WalkingDetector(this, this)
        AnkleDbHelper(this).verifyAndUpdateDatabase()
        checkPermissionsAndStart()
    }

    private fun setupUserInterface() {
        setContent {
            AnkleAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Error dialog if needed
                    errorMessage?.let { error ->
                        AlertDialog(
                            onDismissRequest = { errorMessage = null },
                            title = { Text("Error") },
                            text = { Text(error) },
                            confirmButton = {
                                Button(onClick = { errorMessage = null }) {
                                    Text("OK")
                                }
                            }
                        )
                    }

                    // Main content wrapper with scrolling
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        // Current Status Card
                        StatusCard(
                            isWalking = isWalking,
                            stepCount = stepCount,
                            connectionStatus = connectionStatus,
                            receivedData = receivedData
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Daily Statistics Card
                        DailyStatsCard(
                            dailyStats = dailyStats,
                            lastUpdateTime = lastUpdateTime
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Statistics Dashboard
                        StatisticsDashboard(
                            dataRepository = dataRepository
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatusCard(
        isWalking: Boolean,
        stepCount: Int,
        connectionStatus: String,
        receivedData: String
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Current Status",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Connection status indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                when (connectionStatus) {
                                    "Connected to brace" -> Color.Green
                                    else -> Color.Red
                                },
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(connectionStatus)
                }

                if (receivedData.isNotEmpty()) {
                    Text(
                        text = "Latest Data: $receivedData",
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Text("Activity: ${if (isWalking) "Walking" else "Resting"}")
                Text("Steps: $stepCount")
            }
        }
    }

    @Composable
    private fun DailyStatsCard(
        dailyStats: AnkleDataRepository.DailyStats?,
        lastUpdateTime: LocalDateTime
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Daily Statistics",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                dailyStats?.let { stats ->
                    StatisticsSection(
                        title = "Walking Activity",
                        totalTime = stats.totalWalkingTime,
                        badPostureTime = stats.walkingBadPostureTime
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    StatisticsSection(
                        title = "Resting Activity",
                        totalTime = stats.totalStaticTime,
                        badPostureTime = stats.staticBadPostureTime
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Posture Events",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Text("Left Lean: ${stats.leftEvents}")
                    Text("Right Lean: ${stats.rightEvents}")
                    Text("Forward Lean: ${stats.frontEvents}")

                    Text(
                        text = "Last Updated: ${formatDateTime(lastUpdateTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun StatisticsSection(
        title: String,
        totalTime: Long,
        badPostureTime: Long
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Text("Total Time: ${formatDuration(totalTime)}")
        Text("Bad Posture Time: ${formatDuration(badPostureTime)}")
        val percentage = if (totalTime > 0) {
            (badPostureTime.toFloat() / totalTime.toFloat()) * 100
        } else 0f
        Text("Bad Posture Percentage: ${String.format("%.1f%%", percentage)}")
    }

    @Composable
    private fun StatisticsDashboard(
        dataRepository: AnkleDataRepository
    ) {
        var selectedTab by remember { mutableStateOf("weekly") }
        var weeklyData by remember { mutableStateOf<AnkleDataRepository.WeeklyChartData?>(null) }
        var monthlyData by remember { mutableStateOf<AnkleDataRepository.MonthlyChartData?>(null) }

        LaunchedEffect(selectedTab) {
            when (selectedTab) {
                "weekly" -> {
                    val startOfWeek = LocalDateTime.now()
                        .with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)
                    weeklyData = dataRepository.getWeeklyChartData(startOfWeek)
                }
                "monthly" -> {
                    val startOfMonth = LocalDateTime.now().withDayOfMonth(1)
                    monthlyData = dataRepository.getMonthlyChartData(startOfMonth)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Posture Analysis",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Tab selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    TabButton(
                        text = "Weekly",
                        isSelected = selectedTab == "weekly",
                        onClick = { selectedTab = "weekly" }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TabButton(
                        text = "Monthly",
                        isSelected = selectedTab == "monthly",
                        onClick = { selectedTab = "monthly" }
                    )
                }

                when (selectedTab) {
                    "weekly" -> WeeklyStatsChart(weeklyData)
                    "monthly" -> MonthlyStatsChart(monthlyData)
                }
            }
        }
    }



    // Helper functions and event handlers
    private fun startPeriodicStatsUpdates() {
        lifecycleScope.launch {
            while (isActive) {
                updateDailyStats()
                delay(3000)
            }
        }
    }

    private fun updateDailyStats() {
        try {
            val stats = dataRepository.getDailyStats(LocalDateTime.now())
            runOnUiThread {
                dailyStats = stats
                lastUpdateTime = LocalDateTime.now()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error updating daily stats", e)
        }
    }

    private fun checkPermissionsAndStart() {
        val requiredPermissions = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(requiredPermissions)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        val serviceIntent = Intent(this, WalkingDetectionService::class.java)
        startForegroundService(serviceIntent)
        walkingDetector.startDetection()
        bleManager.startContinuousConnection()
        updateDailyStats()
    }

    private fun showError(message: String) {
        runOnUiThread {
            errorMessage = message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Interface implementations
    override fun onStatsUpdated(stats: AnkleDataRepository.DailyStats) {
        runOnUiThread {
            dailyStats = stats
            lastUpdateTime = LocalDateTime.now()
        }
    }

    override fun onAggregatedStatsUpdated(aggregatedStats: AnkleDataRepository.AggregatedStats) {
        // Since we're updating UI state, we need to make sure we're on the main thread
        runOnUiThread {
            when (aggregatedStats.periodType) {
                "W" -> {
                    // Update weekly statistics state
                    val weekStart = aggregatedStats.startDate
                    weeklyData = dataRepository.getWeeklyChartData(weekStart)
                }
                "M" -> {
                    // Update monthly statistics state
                    val monthStart = aggregatedStats.startDate
                    monthlyData = dataRepository.getMonthlyChartData(monthStart)
                }
            }
            // Update the last refresh time
            lastUpdateTime = LocalDateTime.now()
        }
    }
    override fun onWalkingDetected(isWalking: Boolean) {
        this.isWalking = isWalking
    }

    override fun onStepCounted(count: Int) {
        this.stepCount = count
    }

    override fun onConnected() {
        connectionStatus = "Connected to brace"
    }

    override fun onDisconnected() {
        connectionStatus = "Disconnected from brace"
    }

    override fun onConnectionStopped() {
        connectionStatus = "Connection stopped"
    }

    override fun onDataReceived(data: String) {
        receivedData = data
        dataRepository.handlePostureData(data, isWalking)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.cleanup()
        walkingDetector.stopDetection()
        dataRepository.cleanup()
    }

    // Add these composables to your MainActivity.kt

    @Composable
    private fun WeeklyStatsChart(weeklyData: AnkleDataRepository.WeeklyChartData?) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            weeklyData?.let { data ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.height(400.dp)
                ) {
                    items(data.dailyData) { dayData ->
                        // Calculate percentages for each type of activity
                        val walkingBadPosturePercentage = if (dayData.totalWalkingTime > 0) {
                            (dayData.walkingBadPosture.toFloat() / dayData.totalWalkingTime.toFloat()) * 100
                        } else 0f

                        val staticBadPosturePercentage = if (dayData.totalStaticTime > 0) {
                            (dayData.staticBadPosture.toFloat() / dayData.totalStaticTime.toFloat()) * 100
                        } else 0f

                        DailyStatCard(
                            day = dayData.day,
                            walkingPercentage = walkingBadPosturePercentage,
                            staticPercentage = staticBadPosturePercentage,
                            walkingDuration = dayData.totalWalkingTime,
                            staticDuration = dayData.totalStaticTime
                        )
                    }
                }
            } ?: run {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    @Composable
    private fun DailyStatCard(
        day: String,
        walkingPercentage: Float,
        staticPercentage: Float,
        walkingDuration: Long,
        staticDuration: Long
    ) {
        Card(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(7.dp)
            ) {
                Text(
                    text = day,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Walking stats with percentage
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "Walking",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Bad Posture: %.1f%%".format(walkingPercentage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Resting stats with percentage
                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(
                        text = "Resting",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Bad Posture: %.1f%%".format(staticPercentage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }

    @Composable
    private fun MonthlyStatsChart(monthlyData: AnkleDataRepository.MonthlyChartData?) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            monthlyData?.let { data ->
                // Display weekly summaries
                data.weeklyData.forEachIndexed { index, week ->
                    WeeklySummaryCard(
                        weekNumber = index + 1,
                        weekData = week
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            } ?: run {
                // Loading state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }



    @Composable
    private fun WeeklySummaryCard(weekNumber: Int, weekData: AnkleDataRepository.WeeklyChartData) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Week $weekNumber",
                    style = MaterialTheme.typography.titleMedium
                )

                // Calculate weekly totals
                val totalWalkingTime = weekData.dailyData.sumOf { it.totalWalkingTime }
                val walkingBadPosture = weekData.dailyData.sumOf { it.walkingBadPosture }
                val totalStaticTime = weekData.dailyData.sumOf { it.totalStaticTime }
                val staticBadPosture = weekData.dailyData.sumOf { it.staticBadPosture }

                StatSection(
                    label = "Weekly Walking",
                    totalTime = totalWalkingTime,
                    badPostureTime = walkingBadPosture,
                    color = MaterialTheme.colorScheme.primary
                )

                StatSection(
                    label = "Weekly Resting",
                    totalTime = totalStaticTime,
                    badPostureTime = staticBadPosture,
                    color = MaterialTheme.colorScheme.secondary
                )

                Text(
                    text = "${weekData.startDate} - ${weekData.endDate}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    @Composable
    private fun StatSection(
        label: String,
        totalTime: Long,
        badPostureTime: Long,
        color: Color
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Total: ${formatDuration(totalTime)}",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Bad Posture: ${formatDuration(badPostureTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
                Text(
                    text = "${calculatePercentage(badPostureTime, totalTime)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
        }
    }

    // Add these helper functions if not already present
    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return String.format("%dh %dm %ds", hours, minutes, remainingSeconds)
    }

    private fun calculatePercentage(part: Long, total: Long): String {
        return if (total > 0) {
            String.format("%.1f", (part.toDouble() / total.toDouble() * 100))
        } else "0.0"
    }

    private fun formatDateTime(dateTime: LocalDateTime): String {
        return dateTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }
    @Composable
    private fun TabButton(
        text: String,
        isSelected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Surface(
            modifier = modifier
                .height(40.dp)
                .widthIn(min = 100.dp),
            shape = MaterialTheme.shapes.medium,
            color = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outline
            ),
            onClick = onClick
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
    companion object {
        private const val TAG = "MainActivity"
    }
}