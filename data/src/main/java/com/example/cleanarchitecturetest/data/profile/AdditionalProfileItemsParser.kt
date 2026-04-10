package com.example.cleanarchitecturetest.data.profile

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Parses server payload shaped as `[[{title, web_title, link}, ...], [], ...]`.
 * Empty inner arrays mean a skipped section (no rows for that index).
 *
 * Wire into profile JSON as `psg_additional_profile_items` / `drv_additional_profile_items`
 * (or legacy `psg_additional_items` / `driver_additional_items`) on your profile model.
 */
object AdditionalProfileItemsParser {

    private val DRIVER_KEYS = listOf("drv_additional_profile_items", "driver_additional_items")
    private val PASSENGER_KEYS = listOf("psg_additional_profile_items", "psg_additional_items")

    /**
     * Picks the first present non-null JSON array from [profile] for driver vs passenger.
     */
    fun elementFromProfileJson(profile: JsonObject?, isDriver: Boolean): JsonElement? {
        if (profile == null) return null
        val keys = if (isDriver) DRIVER_KEYS else PASSENGER_KEYS
        for (key in keys) {
            if (!profile.has(key)) continue
            val el = profile.get(key) ?: continue
            if (el.isJsonNull) continue
            if (el.isJsonArray) return el
        }
        return null
    }

    fun parse(element: JsonElement?): List<List<AdditionalProfileItem>>? {
        if (element == null || element.isJsonNull) return null
        if (!element.isJsonArray) return null
        val outer = element.asJsonArray
        if (outer.size() == 0) return emptyList()

        val result = ArrayList<List<AdditionalProfileItem>>(outer.size())
        for (sectionEl in outer) {
            if (!sectionEl.isJsonArray) {
                result.add(emptyList())
                continue
            }
            val inner = sectionEl.asJsonArray
            val items = ArrayList<AdditionalProfileItem>()
            for (itemEl in inner) {
                if (!itemEl.isJsonObject) continue
                val o = itemEl.asJsonObject
                val title = o.stringOrNull("title") ?: continue
                val webTitle = o.stringOrNull("web_title") ?: title
                val link = o.stringOrNull("link") ?: continue
                if (link.isBlank()) continue
                items.add(AdditionalProfileItem(title = title, webTitle = webTitle, link = link))
            }
            result.add(items)
        }
        return result
    }

    fun hasDisplayableItems(sections: List<List<AdditionalProfileItem>>?): Boolean {
        if (sections == null) return false
        return sections.any { it.isNotEmpty() }
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        if (!has(name)) return null
        val el = get(name) ?: return null
        if (el.isJsonNull || !el.isJsonPrimitive) return null
        return try {
            el.asString.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
