package com.librevision

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import com.librevision.data.vision.depth.MetricDepthEngine
import java.io.File
import org.json.JSONObject

class NavigationSmokeInstrumentation : Instrumentation() {
    private var assistantOnly = false
    private var nearbyOnly = false
    private var museOnly = false
    private var directionsOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        assistantOnly = arguments?.getString("check") == "assistant"
        nearbyOnly = arguments?.getString("check") == "nearby"
        museOnly = arguments?.getString("check") == "muse"
        directionsOnly = arguments?.getString("check") == "directions"
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            if (assistantOnly) {
                val report =
                    JSONObject()
                        .put("real_meta_call", true)
                        .put("no_camera_or_microphone_upload", true)
                kotlinx.coroutines.runBlocking {
                    val service =
                        com.librevision.data.remote.GatewayMuseAssistant(
                            com.librevision.data.remote.GatewayPreferences(targetContext)
                        )
                    val start = android.os.SystemClock.elapsedRealtime()
                    val reply =
                        service
                            .ask(
                                com.librevision.domain.assistant.AssistantRequest(
                                    "I want to go to nearest hospital",
                                    "{\"screen\":\"HOME\",\"routeStatus\":\"IDLE\"}",
                                )
                            )
                            .getOrThrow()
                    check(
                        reply.action?.kind ==
                            com.librevision.domain.assistant.MuseActionKind.NAVIGATE_NEARBY &&
                            reply.action.category ==
                                com.librevision.domain.navigation.NearbyCategory.HOSPITAL
                    )
                    report
                        .put("hospital_action", reply.action.kind.name)
                        .put("category", reply.action.category.name)
                        .put("action_output_tokens", reply.outputTokens)
                        .put("action_ms", android.os.SystemClock.elapsedRealtime() - start)
                    val answer =
                        service
                            .ask(
                                com.librevision.domain.assistant.AssistantRequest(
                                    "How do I repeat the current instruction? Explain briefly without doing it.",
                                    "{\"screen\":\"NAVIGATION\"}",
                                )
                            )
                            .getOrThrow()
                    check(!answer.text.isNullOrBlank() && answer.action == null)
                    report
                        .put("answer", answer.text)
                        .put("answer_output_tokens", answer.outputTokens)
                    val origin = com.librevision.domain.navigation.GeoPoint(23.7465, 90.3784)
                    val hospitals =
                        com.librevision.data.routing
                            .PhotonNearbyProvider {
                                com.librevision.data.routing.WalkingServices.PHOTON
                            }
                            .find(com.librevision.domain.navigation.NearbyCategory.HOSPITAL, origin)
                            .getOrThrow()
                    check(hospitals.isNotEmpty())
                    report
                        .put("public_fixture_hospitals", hospitals.size)
                        .put(
                            "nearest_hospital_fixture_m",
                            com.librevision.domain.navigation.RouteGeometry.distance(
                                origin,
                                hospitals.first().point!!,
                            ),
                        )
                }
                output.putString("stream", report.toString() + "\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (directionsOnly) {
                val report =
                    JSONObject().put("public_fixture_only", true).put("gps_is_test_input", true)
                kotlinx.coroutines.runBlocking {
                    val destination =
                        com.librevision.domain.navigation.Destination(
                            "public-test",
                            "Public Dhaka fixture",
                            com.librevision.domain.navigation.GeoPoint(23.72, 90.4),
                        )
                    val fix =
                        com.librevision.domain.navigation.LocationFix(
                            23.7196,
                            90.396,
                            android.os.SystemClock.elapsedRealtime(),
                            5f,
                        )
                    val route =
                        com.librevision.data.routing
                            .ValhallaRouteProvider {
                                com.librevision.data.routing.WalkingServices.VALHALLA
                            }
                            .calculateRoute(destination, fix)
                            .getOrThrow()
                    val engine =
                        com.librevision.domain.navigation.WalkingGuidanceEngine(
                            android.os.SystemClock::elapsedRealtime
                        )
                    engine.start(route)
                    val origin = route.geometry.first()
                    engine.updateLocation(
                        com.librevision.domain.navigation.LocationFix(
                            origin.latitude,
                            origin.longitude,
                            android.os.SystemClock.elapsedRealtime(),
                            5f,
                        )
                    )
                    val guide = engine.currentGuidance().value
                    check(guide.status == com.librevision.domain.navigation.NavigationStatus.ACTIVE)
                    check(guide.message.contains("steps") && guide.totalEstimatedSteps!! > 0)
                    report
                        .put("overview", engine.routeOverview())
                        .put("turn_speech", guide.message)
                        .put("display", guide.displayText)
                        .put("total_metres", guide.totalMeters)
                        .put("total_estimated_steps", guide.totalEstimatedSteps)
                        .put(
                            "provider_spoken_instruction",
                            route.instructions.first().spokenInstruction,
                        )
                }
                output.putString("stream", report.toString() + "\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (museOnly) {
                val image = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(image)
                canvas.drawColor(android.graphics.Color.LTGRAY)
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLUE }
                canvas.drawRect(20f, 60f, 280f, 190f, paint)
                paint.color = android.graphics.Color.WHITE
                paint.textSize = 36f
                canvas.drawText("PHARMACY", 35f, 140f, paint)
                val bytes =
                    java.io.ByteArrayOutputStream().use {
                        image.compress(Bitmap.CompressFormat.JPEG, 80, it)
                        it.toByteArray()
                    }
                image.recycle()
                val start = android.os.SystemClock.elapsedRealtime()
                val result =
                    kotlinx.coroutines.runBlocking {
                        com.librevision.data.remote
                            .GatewaySceneAnalysisProvider(
                                com.librevision.data.remote.GatewayPreferences(targetContext)
                            )
                            .analyze(
                                com.librevision.data.remote.SceneRequest(
                                    bytes,
                                    com.librevision.data.remote.SceneTask.CONTEXTUALIZE_MANEUVER,
                                    com.librevision.data.remote.SceneModel.MUSE_SPARK,
                                    null,
                                    "Turn left.",
                                    emptyList(),
                                    640,
                                    480,
                                )
                            )
                            .getOrThrow()
                    }
                check(result.text.isNotBlank())
                val report =
                    JSONObject()
                        .put("generated_fixture_only", true)
                        .put("real_meta_call", true)
                        .put("text", result.text)
                        .put("output_tokens", result.usage?.output)
                        .put("reasoning_tokens", result.usage?.reasoning)
                        .put("input_tokens", result.usage?.input)
                        .put("upstream_ms", result.latencyMs)
                        .put("round_trip_ms", android.os.SystemClock.elapsedRealtime() - start)
                output.putString("stream", report.toString() + "\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (nearbyOnly) {
                val report = JSONObject().put("public_fixture_only", true)
                kotlinx.coroutines.runBlocking {
                    val origin = com.librevision.domain.navigation.GeoPoint(23.7465, 90.3784)
                    val provider =
                        com.librevision.data.routing.PhotonNearbyProvider {
                            com.librevision.data.routing.WalkingServices.PHOTON
                        }
                    val start = android.os.SystemClock.elapsedRealtime()
                    val places =
                        provider
                            .find(
                                com.librevision.domain.navigation.QuickDestination.PHARMACY,
                                origin,
                            )
                            .getOrThrow()
                    check(places.isNotEmpty())
                    report
                        .put("nearby_pharmacies", places.size)
                        .put("elapsed_ms", android.os.SystemClock.elapsedRealtime() - start)
                    report.put(
                        "nearest_distance_m",
                        com.librevision.domain.navigation.RouteGeometry.distance(
                            origin,
                            places.first().point!!,
                        ),
                    )
                    check(
                        provider
                            .find(
                                com.librevision.domain.navigation.QuickDestination.PHARMACY,
                                origin,
                            )
                            .getOrThrow() == places
                    )
                    report.put("cache_reuse", true)
                }
                output.putString("stream", report.toString() + "\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            val context = targetContext
            val model = File(context.cacheDir, "depth-native-smoke.onnx")
            context.assets.open("monocular_depth.onnx").use { input ->
                model.outputStream().use { input.copyTo(it) }
            }
            val image = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
            for (y in 0 until image.height) for (x in 0 until image.width) image.setPixel(
                x,
                y,
                android.graphics.Color.rgb(x * 255 / 480, y * 255 / 640, 120),
            )
            val times = mutableListOf<Double>()
            MetricDepthEngine(context, model).use { engine ->
                repeat(3) {
                    val result = engine.infer(image, android.os.SystemClock.elapsedRealtime())
                    check(result.values.size == 280 * 280 && result.values.all { it.isFinite() })
                    times += result.inferenceMs
                }
            }
            image.recycle()
            model.delete()
            val report =
                JSONObject()
                    .put("depth_input", 280)
                    .put("depth_output", 280 * 280)
                    .put("runtime", "CPU")
                    .put("threads", 3)
                    .put("inference_ms", org.json.JSONArray(times))
                    .put("synthetic_input_only", true)
            kotlinx.coroutines.runBlocking {
                val places =
                    com.librevision.data.routing
                        .PhotonPlaceSearch { com.librevision.data.routing.WalkingServices.PHOTON }
                        .search("Lalbagh Fort Dhaka", null)
                        .getOrThrow()
                check(places.isNotEmpty())
                report.put("android_search_results", places.size)
                val destination =
                    com.librevision.domain.navigation.Destination(
                        "public-test",
                        "Public Dhaka test point",
                        com.librevision.domain.navigation.GeoPoint(23.72, 90.4),
                    )
                val fix =
                    com.librevision.domain.navigation.LocationFix(
                        23.7196,
                        90.396,
                        android.os.SystemClock.elapsedRealtime(),
                        5f,
                    )
                val route =
                    com.librevision.data.routing
                        .ValhallaRouteProvider {
                            com.librevision.data.routing.WalkingServices.VALHALLA
                        }
                        .calculateRoute(destination, fix)
                        .getOrThrow()
                check(!route.simulated && route.geometry.size > 1)
                report.put("android_route_points", route.geometry.size)
                report.put("android_route_maneuvers", route.instructions.size)
            }
            output.putString("stream", report.toString() + "\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", "FAILED: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
