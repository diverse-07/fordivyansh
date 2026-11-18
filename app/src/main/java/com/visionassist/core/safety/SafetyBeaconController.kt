package com.visionassist.core.safety

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Active Safety Strobe Beacon
 * Uses the device's high-intensity LED torch to alert oncoming traffic,
 * cyclists, and sighted pedestrians of the visually impaired user's presence.
 */
class SafetyBeaconController(context: Context) : SensorEventListener, AutoCloseable {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    
    private val handler = Handler(Looper.getMainLooper())
    private var cameraId: String? = null
    
    val isBeaconActive = AtomicBoolean(false)
    val isAutoNightModeEnabled = AtomicBoolean(true)
    
    @Volatile private var currentLux: Float = 100f
    @Volatile private var isTorchOn: Boolean = false
    @Volatile private var currentPattern: StrobePattern = StrobePattern.OFF

    enum class StrobePattern(val onDurationMs: Long, val offDurationMs: Long) {
        OFF(0, 0),
        APPROACHING_VEHICLE(80, 80),      // Rapid high-urgency strobe for bicycles/cars
        NIGHT_PEDESTRIAN_BEACON(300, 700), // Steady rhythmic pulse for nighttime visibility
        CONTINUOUS_TORCH(1000, 0)          // Constant illumination
    }

    init {
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (facing == CameraCharacteristics.LENS_FACING_BACK && hasFlash) {
                    cameraId = id
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize camera flash hardware", e)
        }

        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    /**
     * Triggers active emergency strobe when an approaching bicycle or vehicle is detected.
     */
    fun triggerVehicleAlertStrobe(durationMs: Long = 3000) {
        setStrobePattern(StrobePattern.APPROACHING_VEHICLE)
        handler.removeCallbacks(strobeResetRunnable)
        handler.postDelayed(strobeResetRunnable, durationMs)
    }

    /**
     * Activates or updates the current strobe mode.
     */
    fun setStrobePattern(pattern: StrobePattern) {
        if (currentPattern == pattern) return
        currentPattern = pattern
        handler.removeCallbacks(strobeRunnable)
        
        if (pattern == StrobePattern.OFF) {
            setTorch(false)
            isBeaconActive.set(false)
        } else {
            isBeaconActive.set(true)
            handler.post(strobeRunnable)
        }
    }

    private val strobeRunnable = object : Runnable {
        override fun run() {
            if (currentPattern == StrobePattern.OFF || cameraId == null) {
                setTorch(false)
                return
            }

            isTorchOn = !isTorchOn
            setTorch(isTorchOn)

            val delay = if (isTorchOn) currentPattern.onDurationMs else currentPattern.offDurationMs
            if (delay > 0) {
                handler.postDelayed(this, delay)
            }
        }
    }

    private val strobeResetRunnable = Runnable {
        if (isAutoNightModeEnabled.get() && currentLux < NIGHT_THRESHOLD_LUX) {
            setStrobePattern(StrobePattern.NIGHT_PEDESTRIAN_BEACON)
        } else {
            setStrobePattern(StrobePattern.OFF)
        }
    }

    private fun setTorch(enabled: Boolean) {
        val id = cameraId ?: return
        try {
            cameraManager.setTorchMode(id, enabled)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Torch mode unavailable: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting torch mode", e)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            currentLux = event.values[0]
            if (isAutoNightModeEnabled.get() && currentPattern == StrobePattern.OFF) {
                if (currentLux < NIGHT_THRESHOLD_LUX) {
                    setStrobePattern(StrobePattern.NIGHT_PEDESTRIAN_BEACON)
                }
            } else if (isAutoNightModeEnabled.get() && currentPattern == StrobePattern.NIGHT_PEDESTRIAN_BEACON) {
                if (currentLux > DAYLIGHT_EXIT_LUX) {
                    setStrobePattern(StrobePattern.OFF)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun close() {
        sensorManager.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
        setTorch(false)
    }

    companion object {
        private const val TAG = "SafetyBeaconController"
        private const val NIGHT_THRESHOLD_LUX = 15.0f // Twilight / dark road threshold
        private const val DAYLIGHT_EXIT_LUX = 35.0f   // Well-lit environment threshold
    }
}
