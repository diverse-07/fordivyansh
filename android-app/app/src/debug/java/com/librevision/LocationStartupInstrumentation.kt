package com.librevision

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.librevision.data.sensors.AndroidNavigationSensors
import com.librevision.domain.navigation.*
import java.io.File
import org.json.JSONObject

/** Measures real provider startup without uploading or saving the user's coordinates. */
class LocationStartupInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        val sensors = AndroidNavigationSensors(targetContext)
        var code = Activity.RESULT_OK
        try {
            startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            val began = SystemClock.elapsedRealtime()
            runOnMainSync { sensors.start() }
            var planningMs: Long? = null
            var liveMs: Long? = null
            while (SystemClock.elapsedRealtime() - began < 15000) {
                val now = SystemClock.elapsedRealtime()
                val fix = sensors.location.value
                if (planningMs == null && NavigationLocationPolicy.usableForPlanning(fix, now))
                    planningMs = now - began
                if (fix != null && now - fix.timestampMs in 0..15000 && fix.accuracyMeters <= 50f) {
                    liveMs = now - began
                    break
                }
                Thread.sleep(100)
            }
            val fix = sensors.location.value
            runOnMainSync { sensors.stop() }
            check(sensors.location.value == fix) {
                "Stopping providers discarded the timestamped planning cache"
            }
            val origin = GeoPoint(23.75, 90.38)
            val turn = GeoPoint(23.751, 90.38)
            val end = GeoPoint(23.751, 90.381)
            val engine = WalkingGuidanceEngine(SystemClock::elapsedRealtime)
            val previewBegan = SystemClock.elapsedRealtime()
            engine.start(
                WalkingRoute(
                    Destination("startup-fixture", "UI fixture", end),
                    listOf("North.", "Turn right.", "End."),
                    false,
                    listOf(origin, turn, end),
                    listOf(
                        RouteManeuver(0, "North.", 1),
                        RouteManeuver(1, "Turn right.", 10),
                        RouteManeuver(2, "End.", 4),
                    ),
                )
            )
            check(engine.currentGuidance().value.displayText?.contains("Turn right") == true)
            val report =
                JSONObject()
                    .put("real_android_location_providers", true)
                    .put("coordinates_saved_or_uploaded", false)
                    .put("planning_fix_ms", planningMs ?: JSONObject.NULL)
                    .put("fresh_guidance_fix_ms", liveMs ?: JSONObject.NULL)
                    .put("fresh_guidance_fix_available", liveMs != null)
                    .put("fix_accuracy_m", fix?.accuracyMeters ?: JSONObject.NULL)
                    .put("cache_survives_stop", true)
                    .put("synthetic_route_preview_ms", SystemClock.elapsedRealtime() - previewBegan)
                    .put("planned_turn_visible_without_fresh_gps", true)
            File(targetContext.filesDir, "location-startup-test.json").writeText(report.toString(2))
            output.putString("stream", report.toString() + "\n")
        } catch (e: Throwable) {
            code = Activity.RESULT_CANCELED
            output.putString("stream", "FAILED: ${e.stackTraceToString()}\n")
        } finally {
            runOnMainSync { sensors.stop() }
        }
        finish(code, output)
    }
}
