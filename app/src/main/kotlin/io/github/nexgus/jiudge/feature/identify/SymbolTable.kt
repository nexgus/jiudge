package io.github.nexgus.jiudge.feature.identify

import android.content.Context
import org.json.JSONObject

/** A map feature's friendly name and (where one exists) the theme symbol drawn for it. */
data class SymbolInfo(
    val name: String,
    val svg: String?,
)

/**
 * Maps a feature's OSM tags to a Chinese label, loaded from the bundled `assets/symbols.json` that
 * `scripts/gen_symbols.py` generates from RudyMap's render theme. The asset ships inside the APK, so
 * this is fully offline. Covers every object the theme can draw; anything unmatched falls back to
 * "未知符號" with its raw tags in the identify card.
 */
class SymbolTable private constructor(
    val version: String,
    private val symbols: Map<String, SymbolInfo>,
    private val keyPriority: List<String>,
    private val surface: Map<String, String>,
    private val adminLevel: Map<String, String>,
) {
    /** Best label for [tags]: checks feature keys in priority order, then administrative boundaries. */
    fun lookup(tags: Map<String, String>): SymbolInfo? {
        for (key in keyPriority) {
            val value = tags[key] ?: continue
            symbols["$key=$value"]?.let { return it }
        }
        if (tags["boundary"] == "administrative") {
            tags["admin_level"]?.let { level ->
                adminLevel[level]?.let { return SymbolInfo(it, null) }
            }
        }
        // The theme draws every building via a wildcard rule, so generic buildings carry no concrete
        // value in the table; label them rather than leaving them "unknown".
        if (tags.containsKey("building")) return SymbolInfo("建築物", null)
        return null
    }

    /** Chinese label for a `surface` value, falling back to the raw value when unmapped. */
    fun surfaceLabel(value: String?): String? = value?.let { surface[it] ?: it }

    companion object {
        const val ASSET = "symbols.json"

        fun load(context: Context): SymbolTable {
            val text =
                context.assets
                    .open(ASSET)
                    .bufferedReader()
                    .use { it.readText() }
            val root = JSONObject(text)

            val symbolsJson = root.getJSONObject("symbols")
            val symbols = HashMap<String, SymbolInfo>(symbolsJson.length() * 2)
            for (tag in symbolsJson.keys()) {
                val o = symbolsJson.getJSONObject(tag)
                val svg = if (o.isNull("svg")) null else o.getString("svg")
                symbols[tag] = SymbolInfo(o.getString("name"), svg)
            }

            val priorityJson = root.getJSONArray("keyPriority")
            val keyPriority = List(priorityJson.length()) { priorityJson.getString(it) }

            fun strMap(name: String): Map<String, String> {
                val o = root.optJSONObject(name) ?: return emptyMap()
                val map = HashMap<String, String>()
                for (k in o.keys()) map[k] = o.getString(k)
                return map
            }

            return SymbolTable(
                version = root.optString("version", ""),
                symbols = symbols,
                keyPriority = keyPriority,
                surface = strMap("surface"),
                adminLevel = strMap("adminLevel"),
            )
        }
    }
}
