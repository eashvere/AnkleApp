package com.example.ankleapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.*

interface WalkingListener {
    fun onWalkingDetected(isWalking: Boolean)
    fun onStepCounted(stepCount: Int)
}

class WalkingDetector(
    context: Context,
    private val listener: WalkingListener
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var stepCount = 0
    var isWalking = false
    private var walkingTimeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val timeoutMillis: Long = 2000

    fun startDetection() {
        stepDetector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopDetection() {
        sensorManager.unregisterListener(this)
        stopWalkingTimeout()
        scope.cancel()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            stepCount++
            listener.onStepCounted(stepCount)
            detectWalking()
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
        walkingTimeoutJob = scope.launch {
            delay(timeoutMillis)
            stopWalking()
        }
    }

    private fun stopWalkingTimeout() {
        walkingTimeoutJob?.cancel()
        walkingTimeoutJob = null
    }

    private fun stopWalking() {
        if (isWalking) {
            isWalking = false
            listener.onWalkingDetected(false)
        }
    }

    fun resetStepCount() {
        stepCount = 0
        listener.onStepCounted(stepCount)
    }

    // Optional: Get current step count
    fun getCurrentStepCount(): Int = stepCount
}