package com.librevision.domain.navigation

import kotlin.math.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun valid() =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0
}

data class RouteManeuver(
    val pointIndex: Int,
    val instruction: String,
    val type: Int,
    val spokenInstruction: String? = null,
)

object RouteGeometry {
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h =
            sin(lat / 2).pow(2) +
                cos(Math.toRadians(a.latitude)) *
                    cos(Math.toRadians(b.latitude)) *
                    sin(lon / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun cumulative(points: List<GeoPoint>): DoubleArray =
        DoubleArray(points.size).also { d ->
            for (i in 1 until points.size) d[i] = d[i - 1] + distance(points[i - 1], points[i])
        }

    data class Match(val progress: Double, val offset: Double)

    fun project(
        point: GeoPoint,
        points: List<GeoPoint>,
        lengths: DoubleArray,
        minimum: Double = 0.0,
        maximum: Double = Double.MAX_VALUE,
    ): Match {
        var best = Match(0.0, Double.POSITIVE_INFINITY)
        val scaleX = cos(Math.toRadians(point.latitude)) * 111195
        val scaleY = 111195.0
        for (i in 0 until points.lastIndex) {
            if (lengths[i + 1] < minimum || lengths[i] > maximum) continue
            val ax = (points[i].longitude - point.longitude) * scaleX
            val ay = (points[i].latitude - point.latitude) * scaleY
            val bx = (points[i + 1].longitude - point.longitude) * scaleX
            val by = (points[i + 1].latitude - point.latitude) * scaleY
            val dx = bx - ax
            val dy = by - ay
            val t =
                if (dx * dx + dy * dy > 0)
                    (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                else 0.0
            val offset = hypot(ax + t * dx, ay + t * dy)
            if (offset < best.offset)
                best = Match(lengths[i] + t * (lengths[i + 1] - lengths[i]), offset)
        }
        return best
    }

    fun decodePolyline6(encoded: String): List<GeoPoint> {
        require(encoded.length in 2..1000000)
        var index = 0
        var lat = 0L
        var lon = 0L
        fun next(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(index < encoded.length && shift <= 30) { "Invalid route geometry" }
                val c = encoded[index++].code - 63
                require(c in 0..63)
                value = value or ((c and 31).toLong() shl shift)
                shift += 5
                if (c < 32) break
            }
            return if (value and 1L != 0L) (value shr 1).inv() else value shr 1
        }
        val points = ArrayList<GeoPoint>()
        while (index < encoded.length) {
            lat += next()
            lon += next()
            val p = GeoPoint(lat / 1e6, lon / 1e6)
            require(p.valid())
            points += p
            require(points.size <= 50000)
        }
        require(points.size >= 2)
        return points
    }
}

/** GPS advances mapped geometry only; vision/depth never supply a turn or route distance. */
class WalkingGuidanceEngine(private val now: () -> Long) : NavigationGuidanceEngine {
    private val mutable = MutableStateFlow(GuidanceState())

    override fun currentGuidance(): StateFlow<GuidanceState> = mutable

    private var route: WalkingRoute? = null
    private var lengths = doubleArrayOf()
    private var progress = 0.0
    private var lastFix: LocationFix? = null
    private var offCount = 0
    private var arrivalCount = 0
    private var stepLength = .65f
    private var nextManeuver = 0

    fun start(value: WalkingRoute) {
        require(
            !value.simulated &&
                value.geometry.size >= 2 &&
                value.geometry.all { it.valid() } &&
                value.instructions.isNotEmpty()
        )
        require(value.instructions.all { it.pointIndex in value.geometry.indices })
        route = value
        lengths = RouteGeometry.cumulative(value.geometry)
        progress = 0.0
        lastFix = null
        offCount = 0
        arrivalCount = 0
        nextManeuver =
            value.instructions
                .indexOfFirst { lengths[it.pointIndex] > 0 || it.type !in 1..3 }
                .let { if (it < 0) value.instructions.lastIndex else it }
        mutable.value =
            GuidanceState(
                NavigationStatus.WAITING_FOR_GPS,
                "Route ready",
                totalMeters = lengths.last(),
                totalEstimatedSteps = NavigationMeasures.steps(lengths.last(), stepLength),
            )
        waitingForLocation()
    }

    fun setStepLength(value: Float) {
        stepLength = NavigationMeasures.validStride(value)
        val state = mutable.value
        mutable.value =
            state.copy(
                totalEstimatedSteps =
                    state.totalMeters?.let { NavigationMeasures.steps(it, stepLength) },
                remainingEstimatedSteps =
                    state.remainingMeters?.let { NavigationMeasures.steps(it, stepLength) },
            )
        if (route != null && state.status == NavigationStatus.ACTIVE)
            lastFix?.takeIf { now() - it.timestampMs in 0..15000 }?.let(::publishGuidance)
    }

    fun routeOverview(): String? = route?.let {
        NavigationMeasures.totalSpeech(lengths.last(), stepLength)
    }

    fun stop() {
        route = null
        lastFix = null
        mutable.value = GuidanceState(NavigationStatus.STOPPED, "Navigation stopped.")
    }

    fun tick() {
        if (
            route != null &&
                mutable.value.status != NavigationStatus.ARRIVED &&
                (lastFix == null || now() - lastFix!!.timestampMs > 15000)
        )
            waitingForLocation()
    }

    override fun updateLocation(location: LocationFix) {
        val plan = route ?: return
        if (mutable.value.status == NavigationStatus.ARRIVED) return
        val p = GeoPoint(location.latitude, location.longitude)
        if (
            !p.valid() ||
                location.timestampMs > now() ||
                now() - location.timestampMs > 15000 ||
                location.accuracyMeters !in 0f..250f ||
                lastFix?.let { location.timestampMs <= it.timestampMs } == true
        )
            return
        val previous = lastFix
        if (previous != null) {
            val dt = (location.timestampMs - previous.timestampMs) / 1000.0
            if (
                RouteGeometry.distance(GeoPoint(previous.latitude, previous.longitude), p) >
                    max(25.0, dt * 4 + location.accuracyMeters + previous.accuracyMeters)
            )
                return
        }
        lastFix = location
        if (location.accuracyMeters > 50f) {
            waitingForLocation(location.timestampMs)
            return
        }
        val match =
            RouteGeometry.project(
                p,
                plan.geometry,
                lengths,
                (progress - 40).coerceAtLeast(0.0),
                progress + 150,
            )
        val total = lengths.last()
        if (match.offset > max(25.0, location.accuracyMeters * 1.5))
            offCount = if (previous == null) 2 else offCount + 1
        else offCount = 0
        if (offCount >= 2) {
            mutable.value =
                mutable.value.copy(
                    status = NavigationStatus.OFF_ROUTE,
                    message = "Route deviation detected. Stop and recalculate the walking route.",
                    displayText = null,
                    lastLocationTimestampMs = location.timestampMs,
                    speechKey = "off-route",
                )
            return
        }
        if (match.offset <= max(20.0, location.accuracyMeters.toDouble()))
            progress = max(progress, match.progress)
        val endpointGap =
            plan.destination.point?.let { RouteGeometry.distance(plan.geometry.last(), it) }
                ?: Double.POSITIVE_INFINITY
        if (
            total - progress < 20 &&
                RouteGeometry.distance(p, plan.geometry.last()) < 15 &&
                location.accuracyMeters <= 15 &&
                endpointGap <= 30
        )
            arrivalCount++
        else arrivalCount = 0
        if (arrivalCount >= 2) {
            mutable.value =
                mutable.value.copy(
                    status = NavigationStatus.ARRIVED,
                    message =
                        "Near the mapped route end. The destination entrance is not verified.",
                    remainingMeters = 0.0,
                    remainingEstimatedSteps = 0,
                    maneuverDistanceMeters = null,
                    maneuverEstimatedSteps = null,
                    speechKey = "arrived",
                    lastLocationTimestampMs = location.timestampMs,
                )
            return
        }
        // Keep the upcoming turn until it has actually been passed, rather than skipping it 8 m
        // early.
        while (
            nextManeuver < plan.instructions.lastIndex &&
                progress >
                    lengths[plan.instructions[nextManeuver].pointIndex] +
                        max(3.0, min(location.accuracyMeters.toDouble(), 15.0) / 2)
        ) nextManeuver++
        publishGuidance(location)
    }

    private fun waitingForLocation(timestamp: Long? = mutable.value.lastLocationTimestampMs) {
        val plan = route ?: return
        val maneuver = plan.instructions[nextManeuver]
        val instruction = shortInstruction(maneuver).trimEnd('.')
        mutable.value =
            mutable.value.copy(
                status = NavigationStatus.WAITING_FOR_GPS,
                message =
                    "Route preview. Next mapped maneuver: $instruction. Live location is updating.",
                displayText = "Next: $instruction\nLocation updating · route preview",
                maneuverIndex = nextManeuver,
                remainingMeters = null,
                remainingEstimatedSteps = null,
                maneuverDistanceMeters = null,
                maneuverEstimatedSteps = null,
                lastLocationTimestampMs = timestamp,
                speechKey = "",
            )
    }

    private fun publishGuidance(location: LocationFix) {
        val plan = route ?: return
        val total = lengths.last()
        val next = nextManeuver
        val maneuver = plan.instructions[next]
        val distance = (lengths[maneuver.pointIndex] - progress).coerceAtLeast(0.0)
        val band =
            when {
                distance <= 12 -> "near"
                distance <= 35 -> "soon"
                distance <= 80 -> "approach"
                distance <= 200 -> "prepare"
                distance <= 1000 -> "far"
                else -> "long"
            }
        val caution = requiresAssessment(maneuver)
        val instruction =
            when {
                caution -> "Crossing or level change ahead. Stop and assess."
                maneuver.type in 4..6 -> "Mapped route end ahead. Entrance not verified."
                else -> shortInstruction(maneuver)
            }
        val imprecise =
            location.accuracyMeters > 15f && distance <= max(35.0, location.accuracyMeters * 2.0)
        val currentAction =
            if (progress < 10 && next > 0) shortInstruction(plan.instructions.first()) + " " else ""
        val text =
            when {
                imprecise -> "Upcoming: $instruction Location approximate."
                caution ->
                    if (distance < 5) instruction
                    else "In ${NavigationMeasures.toTurnSpeech(distance,stepLength)}: $instruction"
                maneuver.type in 4..6 ->
                    if (distance < 5) instruction
                    else
                        "${currentAction}Route end in ${NavigationMeasures.toTurnSpeech(distance,stepLength)}. Entrance not verified."
                distance < 5 ->
                    if (maneuver.type in 4..6) instruction else "${instruction.trimEnd('.')} ahead."
                else ->
                    "$currentAction${instruction.trimEnd('.')} in ${NavigationMeasures.toTurnSpeech(distance,stepLength)}."
            }
        val label =
            when (maneuver.type) {
                9 -> "Bear right"
                10 -> "Right"
                11 -> "Sharp right"
                12,
                13 -> "U-turn"
                14 -> "Sharp left"
                15 -> "Left"
                16 -> "Bear left"
                22 -> "Straight"
                23 -> "Keep right"
                24 -> "Keep left"
                else -> instruction.trimEnd('.')
            }
        val compact =
            when {
                caution -> "Crossing/level change: assess"
                maneuver.type in 4..6 ->
                    "Route end (unverified) · ${NavigationMeasures.display(distance)}"
                imprecise -> "Upcoming: $label · location approximate"
                distance < 5 -> "$label ahead"
                else ->
                    "$label · ${NavigationMeasures.display(distance)} · ≈${NavigationMeasures.number(NavigationMeasures.turnSteps(distance,stepLength))} steps"
            }
        mutable.value =
            mutable.value.copy(
                status = NavigationStatus.ACTIVE,
                message = text,
                displayText = compact + "\n" + NavigationMeasures.totalDisplay(total, stepLength),
                remainingMeters = (total - progress).coerceAtLeast(0.0),
                remainingEstimatedSteps =
                    NavigationMeasures.steps((total - progress).coerceAtLeast(0.0), stepLength),
                totalMeters = total,
                totalEstimatedSteps = NavigationMeasures.steps(total, stepLength),
                maneuverDistanceMeters = if (imprecise) null else distance,
                maneuverEstimatedSteps =
                    if (imprecise) null else NavigationMeasures.turnSteps(distance, stepLength),
                lastLocationTimestampMs = location.timestampMs,
                maneuverIndex = next,
                speechKey = "$next-$band-${if(imprecise)"approx"else "metric"}",
            )
    }

    override fun updateHeading(heading: HeadingEstimate) {
        mutable.value =
            mutable.value.copy(
                headingReliable = heading.reliable && heading.degrees?.isFinite() == true
            )
    }

    companion object {
        private val assessmentWords =
            Regex("""\b(cross|crossing|stairs|steps|ferry)\b""", RegexOption.IGNORE_CASE)

        private fun requiresAssessment(maneuver: RouteManeuver) =
            maneuver.type in setOf(28, 29, 39, 40, 41) ||
                assessmentWords.containsMatchIn(maneuver.instruction)

        fun shortInstruction(maneuver: RouteManeuver): String {
            // Keep crossing/level-change caution even if a provider's short instruction omits it.
            if (requiresAssessment(maneuver))
                return "Crossing or level change ahead. Stop and assess."
            // Standard maneuver IDs from Valhalla's documented route contract.
            return when (maneuver.type) {
                8 -> "Continue."
                9 -> "Bear right."
                10 -> "Turn right."
                11 -> "Turn sharply right."
                12,
                13 -> "Make a U-turn."
                14 -> "Turn sharply left."
                15 -> "Turn left."
                16 -> "Bear left."
                22 -> "Continue straight."
                23 -> "Keep right."
                24 -> "Keep left."
                else ->
                    (maneuver.spokenInstruction ?: maneuver.instruction)
                        .replace(Regex("\\s+(?i:then)\\b.*"), "")
                        .replace(
                            Regex(
                                """(?i)\s+\b(?:for|in)\s+(?:about\s+|approximately\s+)?\d+(?:\.\d+)?\s*(?:feet|foot|ft|yards?|yd|miles?|mi|metres?|meters?|kilometres?|kilometers?|km|m)\b"""
                            ),
                            "",
                        )
                        .replace(" .", ".")
                        .trim()
                        .take(120)
            }
        }

        fun safeInstruction(text: String): String =
            if (assessmentWords.containsMatchIn(text))
                "Mapped crossing or level change ahead. Stop and assess it independently."
            else text.take(240)
    }
}
