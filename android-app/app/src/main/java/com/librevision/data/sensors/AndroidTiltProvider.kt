package com.librevision.data.sensors

import android.content.Context
import android.hardware.*
import android.view.Surface
import com.librevision.domain.vision.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidTiltProvider(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor =
        manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val state = MutableStateFlow(PhoneTilt.UNKNOWN)
    val tilt: StateFlow<PhoneTilt> = state
    private val gravity = FloatArray(3)
    private var rotation: () -> Int = { Surface.ROTATION_0 }

    fun start(displayRotation: () -> Int) {
        rotation = displayRotation
        gravity.fill(0f)
        if (
            sensor == null ||
                manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) != true
        )
            state.value = PhoneTilt.UNKNOWN
    }

    fun stop() {
        manager?.unregisterListener(this)
        state.value = PhoneTilt.UNKNOWN
    }

    override fun onSensorChanged(event: SensorEvent) {
        for (i in 0..2) gravity[i] =
            if (event.sensor.type == Sensor.TYPE_GRAVITY) event.values[i]
            else .8f * gravity[i] + .2f * event.values[i]
        state.value = TiltPolicy.classify(gravity[0], gravity[1], gravity[2], rotation())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) state.value = PhoneTilt.UNKNOWN
    }
}
