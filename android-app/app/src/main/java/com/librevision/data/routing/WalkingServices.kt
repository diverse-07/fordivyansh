package com.librevision.data.routing

import android.content.Context
import com.librevision.domain.navigation.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*

object WalkingServices {
    const val USER_AGENT = "NetraSaathi/1.0 (Android pedestrian accessibility)"
    const val PHOTON = "https://photon.komoot.io/api/"
    const val VALHALLA = "https://valhalla1.openstreetmap.de/route"
    const val TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0"))
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }
        .getOrDefault(false)

    suspend fun json(
        url: String,
        body: String? = null,
        contentType: String = "application/json",
    ): String =
        withContext(Dispatchers.IO) {
            require(validUrl(url)) { "An HTTPS service URL is required" }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 12000
            connection.readTimeout = 18000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("X-Client-Id", "NetraSaathiAndroid")
            connection.setRequestProperty("Accept", "application/json")
            try {
                if (body != null) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", contentType)
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                check(status in 200..299) {
                    if (status == 429) "Service rate limit reached. Please wait before retrying."
                    else "Walking service unavailable (HTTP $status)."
                }
                connection.inputStream.bufferedReader().use { reader ->
                    val result = StringBuilder()
                    val buffer = CharArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = reader.read(buffer)
                        if (n < 0) break
                        result.append(buffer, 0, n)
                        check(result.length <= 4000000) { "Service response too large" }
                    }
                    result.toString()
                }
            } finally {
                connection.disconnect()
            }
        }
}

class ServicePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("walking_services", Context.MODE_PRIVATE)
    var search: String
        get() = prefs.getString("search", WalkingServices.PHOTON)!!
        set(value) {
            require(WalkingServices.validUrl(value))
            prefs.edit().putString("search", value).apply()
        }

    var routing: String
        get() = prefs.getString("routing", WalkingServices.VALHALLA)!!
        set(value) {
            require(WalkingServices.validUrl(value))
            prefs.edit().putString("routing", value).apply()
        }

    var tiles: String
        get() = prefs.getString("tiles", WalkingServices.TILES)!!
        set(value) {
            require(
                WalkingServices.validUrl(value) && listOf("{z}", "{x}", "{y}").all { it in value }
            )
            prefs.edit().putString("tiles", value).apply()
        }
}

interface PlaceSearchProvider {
    suspend fun search(query: String, near: GeoPoint?): Result<List<Destination>>
}

class PhotonPlaceSearch(private val endpoint: () -> String) : PlaceSearchProvider {
    private val mutex = Mutex()
    private var lastRequest = 0L
    private val cache = LinkedHashMap<String, List<Destination>>()

    override suspend fun search(query: String, near: GeoPoint?): Result<List<Destination>> =
        try {
            require(query.trim().length in 3..160) { "Enter at least three characters" }
            val key =
                endpoint() +
                    query.trim().lowercase() +
                    near?.let { "${(it.latitude*10).toInt()},${(it.longitude*10).toInt()}" }
            val values = mutex.withLock {
                cache[key]?.let {
                    return@withLock it
                }
                val wait = 1100 - (System.nanoTime() / 1000000 - lastRequest)
                if (wait > 0) delay(wait)
                lastRequest = System.nanoTime() / 1000000
                val location = near?.let { "&lat=${it.latitude}&lon=${it.longitude}" } ?: ""
                val separator = if ('?' in endpoint()) "&" else "?"
                val url =
                    endpoint() +
                        separator +
                        "q=" +
                        URLEncoder.encode(query.trim(), "UTF-8") +
                        "&limit=6&lang=en" +
                        location
                val raw = WalkingServices.json(url)
                val parsed = withContext(Dispatchers.Default) { WalkingResponseParser.places(raw) }
                if (cache.size >= 32) cache.remove(cache.keys.first())
                cache[key] = parsed
                parsed
            }
            Result.success(values)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
}

class ValhallaRouteProvider(private val endpoint: () -> String) : RouteProvider {
    override suspend fun calculateRoute(
        destination: Destination,
        origin: LocationFix?,
    ): Result<WalkingRoute> =
        try {
            require(origin != null && destination.point?.valid() == true) {
                "A current location and real destination are required"
            }
            val start = GeoPoint(origin.latitude, origin.longitude)
            require(start.valid())
            val body =
                JSONObject()
                    .put(
                        "locations",
                        JSONArray()
                            .put(
                                JSONObject().put("lat", start.latitude).put("lon", start.longitude)
                            )
                            .put(
                                JSONObject()
                                    .put("lat", destination.point!!.latitude)
                                    .put("lon", destination.point.longitude)
                            ),
                    )
                    .put("costing", "pedestrian")
                    .put("units", "kilometers")
                    .put("language", "en-US")
                    .put("alternates", 0)
            val raw = WalkingServices.json(endpoint(), body.toString())
            Result.success(
                withContext(Dispatchers.Default) {
                    WalkingResponseParser.route(raw, destination, start)
                }
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
}

object WalkingResponseParser {
    fun places(raw: String): List<Destination> {
        val features = JSONObject(raw).getJSONArray("features")
        require(features.length() <= 100)
        return (0 until features.length())
            .mapNotNull { i ->
                val f = features.getJSONObject(i)
                val geometry = f.optJSONObject("geometry") ?: return@mapNotNull null
                if (geometry.optString("type") != "Point") return@mapNotNull null
                val xy = geometry.getJSONArray("coordinates")
                val p = GeoPoint(xy.getDouble(1), xy.getDouble(0))
                if (!p.valid()) return@mapNotNull null
                val props = f.getJSONObject("properties")
                val name =
                    listOf("name", "street", "city", "country")
                        .map { props.optString(it) }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(", ")
                        .take(180)
                if (name.isBlank()) return@mapNotNull null
                Destination(
                    "${props.optString("osm_type")}-${props.optString("osm_id")}-${p.latitude}-${p.longitude}",
                    name,
                    p,
                )
            }
            .distinctBy { it.point }
            .take(6)
    }

    fun route(raw: String, destination: Destination, origin: GeoPoint): WalkingRoute {
        val trip = JSONObject(raw).getJSONObject("trip")
        require(trip.optInt("status", -1) == 0) { "No pedestrian route was returned" }
        val legs = trip.getJSONArray("legs")
        require(legs.length() == 1) { "Unexpected route structure" }
        val leg = legs.getJSONObject(0)
        val points = RouteGeometry.decodePolyline6(leg.getString("shape"))
        require(RouteGeometry.distance(points.first(), origin) <= 100) {
            "Route start is too far from the current location"
        }
        val maneuvers = leg.getJSONArray("maneuvers")
        require(maneuvers.length() in 1..2000)
        val steps =
            (0 until maneuvers.length()).map { i ->
                val m = maneuvers.getJSONObject(i)
                val index = m.getInt("begin_shape_index")
                require(index in points.indices)
                val instruction = m.getString("instruction").trim()
                require(instruction.length in 1..1000)
                RouteManeuver(
                    index,
                    instruction.take(300),
                    m.getInt("type"),
                    m.optString("verbal_succinct_transition_instruction")
                        .trim()
                        .ifBlank { m.optString("verbal_transition_alert_instruction").trim() }
                        .take(160)
                        .ifBlank { null },
                )
            }
        require(steps.zipWithNext().all { it.first.pointIndex <= it.second.pointIndex })
        return WalkingRoute(
            destination,
            steps.map { it.instruction },
            false,
            points,
            steps,
            origin,
            "Valhalla pedestrian · OpenStreetMap data",
        )
    }
}
