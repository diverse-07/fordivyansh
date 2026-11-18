package com.librevision.data.models

/** Counts bytes downloaded, validated from cache, or copied from the APK; readiness owns 100%. */
class PreparationProgress(private val sizes: Map<String, Long>) {
    init {
        require(sizes.isNotEmpty() && sizes.values.all { it > 0 })
    }

    private val completed = mutableMapOf<String, Long>()

    fun update(key: String, bytes: Long): Int {
        val size = checkNotNull(sizes[key])
        completed[key] = maxOf(completed[key] ?: 0, bytes.coerceIn(0, size))
        return percentage
    }

    val percentage
        get() =
            ((completed.values.sum().toDouble() / sizes.values.sum()) * 100).toInt().coerceIn(0, 99)
}
