package com.librevision.data.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.*
import androidx.core.content.ContextCompat
import com.librevision.domain.navigation.*
import kotlin.math.*
import kotlinx.coroutines.flow.*

class AndroidNavigationSensors(private val context: Context) :
    LocationListener, SensorEventListener, OrientationProvider {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotation = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val mutableFix = MutableStateFlow<LocationFix?>(null)
    val location = mutableFix.asStateFlow()
    private val mutableStatus = MutableStateFlow("Location is not enabled.")
    val status = mutableStatus.asStateFlow()
    private val mutableHeading = MutableStateFlow(HeadingEstimate(null, false))
    override val heading = mutableHeading.asStateFlow()
    private val matrix = FloatArray(9)
    private var running = false
    private var reliable = false
    private var headingAt = 0L
    private var generation = 0L
    private val cancellations = mutableListOf<CancellationSignal>()

    @Suppress("MissingPermission")
    fun start() {
        if (running) return
        val fine =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            mutableStatus.value = "Location permission is required for walking guidance."
            return
        }
        running = true
        val session = ++generation
        mutableStatus.value = "Locating…"
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        var registered = false
        for (provider in providers) {
            // One unavailable provider must not cancel working network/GPS/fused subscriptions.
            if (!runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false))
                continue
            runCatching { locationManager.getLastKnownLocation(provider) }
                .getOrNull()
                ?.let(::onLocationChanged)
            if (
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        1000L,
                        0f,
                        this,
                        Looper.getMainLooper(),
                    )
                }
                    .isSuccess
            )
                registered = true
            if (Build.VERSION.SDK_INT >= 30) {
                val cancellation = CancellationSignal()
                cancellations += cancellation
                runCatching {
                    locationManager.getCurrentLocation(
                        provider,
                        cancellation,
                        context.mainExecutor,
                    ) { fix ->
                        if (running && generation == session && fix != null) onLocationChanged(fix)
                    }
                }
            }
        }
        if (!registered) mutableStatus.value = "Enable device Location to follow your position."
        if (rotation != null)
            sensorManager?.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
    }

    override fun onLocationChanged(location: Location) {
        if (!running || !location.hasAccuracy()) return
        val fix =
            LocationFix(
                location.latitude,
                location.longitude,
                location.elapsedRealtimeNanos / 1000000,
                location.accuracy,
            )
        if (
            !NavigationLocationPolicy.acceptsUpdate(
                mutableFix.value,
                fix,
                SystemClock.elapsedRealtime(),
            )
        )
            return
        mutableFix.value = fix
        mutableStatus.value =
            if (fix.accuracyMeters > 50f) "Approximate location" else "Location ready"
    }

    override fun onProviderDisabled(provider: String) {
        mutableStatus.value = "Location updating…"
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        headingAt = SystemClock.elapsedRealtime()
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val east = -matrix[2]
        val north = -matrix[5]
        if (hypot(east, north) < .3f) {
            mutableHeading.value = HeadingEstimate(null, false)
            return
        }
        val point = mutableFix.value
        val declination =
            point?.let {
                GeomagneticField(
                        it.latitude.toFloat(),
                        it.longitude.toFloat(),
                        0f,
                        System.currentTimeMillis(),
                    )
                    .declination
            } ?: 0f
        val degrees =
            ((Math.toDegrees(atan2(east, north).toDouble()) + declination + 360) % 360).toFloat()
        mutableHeading.value = HeadingEstimate(degrees, reliable && point != null)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        if (!reliable) mutableHeading.value = HeadingEstimate(null, false)
    }

    fun tick() {
        if (running && SystemClock.elapsedRealtime() - headingAt > 3000)
            mutableHeading.value = HeadingEstimate(null, false)
        if (
            running &&
                mutableFix.value?.let { SystemClock.elapsedRealtime() - it.timestampMs > 15000 } ==
                    true
        )
            mutableStatus.value = "Updating location…"
    }

    fun stop() {
        running = false
        generation++
        cancellations.forEach { it.cancel() }
        cancellations.clear()
        locationManager.removeUpdates(this)
        sensorManager?.unregisterListener(this)
        mutableHeading.value = HeadingEstimate(null, false)
        // Keep the timestamped cache across screens; the planning/guidance policies enforce
        // freshness.
    }
}
