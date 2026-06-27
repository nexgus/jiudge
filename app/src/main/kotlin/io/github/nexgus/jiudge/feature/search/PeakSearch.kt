package io.github.nexgus.jiudge.feature.search

import io.github.nexgus.jiudge.core.index.Peak

/**
 * Pure substring search over the loaded peak index. Matching is case-insensitive on the raw label
 * (RudyMap bakes the height into the name, e.g. "玉山, 3952m", so a bare "玉山" query still matches).
 *
 * Ordering favours the most likely target: names that *start* with the query come first (so "玉山"
 * ranks 玉山 above 南玉山), then higher summits before lower ones (a hiker searching a common name
 * usually wants the famous high peak), then alphabetical for stability.
 */
object PeakSearch {
    /** Max rows the dialog shows; a wider match set is capped with a "refine your query" hint. */
    const val MAX_RESULTS = 50

    fun search(
        peaks: List<Peak>,
        query: String,
    ): List<Peak> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase()
        return peaks
            .filter { it.name.lowercase().contains(lower) }
            .sortedWith(
                compareByDescending<Peak> { it.name.lowercase().startsWith(lower) }
                    .thenByDescending { it.eleMeters ?: Int.MIN_VALUE }
                    .thenBy { it.name },
            )
    }
}
