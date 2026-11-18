package com.librevision.data.routing

import com.librevision.domain.navigation.*
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

interface NearbyDestinationProvider {
    suspend fun find(kind: NearbyCategory, origin: GeoPoint): Result<List<Destination>>

    suspend fun find(kind: QuickDestination, origin: GeoPoint): Result<List<Destination>> =
        find(kind.category, origin)
}

/** Category-filtered reverse lookup uses the same Photon service as named destination search. */
class PhotonNearbyProvider(private val endpoint: () -> String) : NearbyDestinationProvider {
    private val mutex = Mutex()
    private var lastRequest = 0L
    private val cache = LinkedHashMap<String, Pair<Long, List<Destination>>>()

    override suspend fun find(kind: NearbyCategory, origin: GeoPoint): Result<List<Destination>> =
        try {
            require(origin.valid())
            val service = endpoint()
            val key =
                "$service-${kind.name}-${(origin.latitude*200).toInt()}-${(origin.longitude*200).toInt()}"
            val result = mutex.withLock {
                val now = System.nanoTime() / 1000000
                cache[key]
                    ?.takeIf { now - it.first < 300000 }
                    ?.let {
                        return@withLock it.second
                            .filter { d -> RouteGeometry.distance(origin, d.point!!) <= 3000 }
                            .sortedBy { d -> RouteGeometry.distance(origin, d.point!!) }
                    }
                val wait = 1100 - (now - lastRequest)
                if (wait > 0) delay(wait)
                lastRequest = System.nanoTime() / 1000000
                val raw = WalkingServices.json(url(service, kind, origin))
                val places = withContext(Dispatchers.Default) { parse(raw, kind, origin) }
                if (cache.size >= 16) cache.remove(cache.keys.first())
                cache[key] = System.nanoTime() / 1000000 to places
                places
            }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    companion object {
        fun url(endpoint: String, kind: QuickDestination, origin: GeoPoint) =
            url(endpoint, kind.category, origin)

        fun parse(raw: String, kind: QuickDestination, origin: GeoPoint) =
            parse(raw, kind.category, origin)

        fun url(endpoint: String, kind: NearbyCategory, origin: GeoPoint): String {
            require(origin.valid() && WalkingServices.validUrl(endpoint))
            val source = URI(endpoint)
            val path = source.path.trimEnd('/').removeSuffix("/api") + "/reverse"
            val reverse = URI(source.scheme, source.authority, path, null, null).toString()
            val tags =
                kind.filters.joinToString("") { (key, value) ->
                    "&osm_tag=" + URLEncoder.encode("$key:$value", "UTF-8")
                }
            val query = source.rawQuery?.let { "$it&" }.orEmpty()
            return "$reverse?${query}lat=${origin.latitude}&lon=${origin.longitude}&radius=3&limit=6&lang=en$tags"
        }

        fun parse(raw: String, kind: NearbyCategory, origin: GeoPoint): List<Destination> {
            val features = JSONObject(raw).getJSONArray("features")
            require(features.length() <= 100)
            val matching = JSONArray()
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val props = feature.optJSONObject("properties") ?: continue
                if (
                    kind.filters.any { (key, value) ->
                        props.optString("osm_key") == key && props.optString("osm_value") == value
                    }
                ) {
                    if (props.optString("name").isBlank()) props.put("name", kind.label)
                    matching.put(feature)
                }
            }
            return WalkingResponseParser.places(JSONObject().put("features", matching).toString())
                .filter { RouteGeometry.distance(origin, it.point!!) <= 3000 }
                .sortedBy { RouteGeometry.distance(origin, it.point!!) }
                .take(6)
        }
    }
}
