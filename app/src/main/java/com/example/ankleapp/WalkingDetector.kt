package com.example.ankleapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.*

interface WalkingListener {
    fun onWalkingDetected(isWalking: Boolean)
}

class WalkingDetector(
    context: Context,
    private val listener: WalkingListener
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var isWalking = false
    private var walkingTimeoutJob: Job? = null

    private val timeoutMillis: Long = 1000 // Shorter timeout for quicker stop detection

    fun startDetection() {
        stepDetector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopDetection() {
        sensorManager.unregisterListener(this)
        stopWalkingTimeout() // Stop any active timeout when detection stops
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            detectWalking() // Triggered each time a step is detected
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }

    private fun detectWalking() {
        // If we are not already in a "walking" state, notify that walking has started
        if (!isWalking) {
            isWalking = true
            listener.onWalkingDetected(true)
        }

        // Cancel any previous timeout to prevent premature stopping
        stopWalkingTimeout()

        // Start a new timeout that will trigger stop walking if no steps occur in the timeout period
        walkingTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(timeoutMillis)
            stopWalking() // If no further steps detected within timeoutMillis, assume stopped
        }
    }

    private fun stopWalkingTimeout() {
        walkingTimeoutJob?.cancel()
        walkingTimeoutJob = null
    }

    private fun stopWalking() {
        if (isWalking) {
            isWalking = false
            listener.onWalkingDetected(false) // Notify listener that walking has stopped
        }
    }
}
