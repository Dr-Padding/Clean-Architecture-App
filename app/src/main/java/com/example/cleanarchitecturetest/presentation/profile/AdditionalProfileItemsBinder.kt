package com.example.cleanarchitecturetest.presentation.profile

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.example.cleanarchitecturetest.data.profile.AdditionalProfileItem
import com.example.cleanarchitecturetest.data.profile.AdditionalProfileItemsParser
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Binds [CardView] + [LinearLayout] container to nested additional profile rows.
 * Opens WebView via [openWebView]; in Aparu use `WebViewActivity` with `title` and `url` extras.
 *
 * **Integrate in `profile2.xml`:** after `notificationsAndSoundLayout`, before `passengerReportsCardView`:
 * `<include layout="@layout/profile_additional_items_card" />`
 *
 * **In `ProfileActivity2`:** after `findViewById` / view binding, call
 * [bindFromProfileJsonString] with `session.getDriverProfile()` (or your Gson model’s JSON) and
 * `session.isDriver()`.
 */
object AdditionalProfileItemsBinder {

    fun bindFromProfileJsonString(
        cardView: CardView,
        container: LinearLayout,
        profileJson: String?,
        isDriver: Boolean,
        openWebView: (context: Context, title: String, url: String) -> Unit,
    ) {
        val root = try {
            if (profileJson.isNullOrBlank()) null
            else JsonParser.parseString(profileJson).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        }
        bind(cardView, container, root, isDriver, openWebView)
    }

    fun bind(
        cardView: CardView,
        container: LinearLayout,
        profile: JsonObject?,
        isDriver: Boolean,
        openWebView: (context: Context, title: String, url: String) -> Unit,
    ) {
        val element = AdditionalProfileItemsParser.elementFromProfileJson(profile, isDriver)
        val sections = AdditionalProfileItemsParser.parse(element)
        val visible = AdditionalProfileItemsParser.hasDisplayableItems(sections)
        cardView.visibility = if (visible) View.VISIBLE else View.GONE
        container.removeAllViews()
        if (!visible || sections == null) return

        val inflater = LayoutInflater.from(container.context)
        var isFirstSection = true
        for (items in sections) {
            if (items.isEmpty()) continue
            if (!isFirstSection) {
                inflater.inflate(com.example.cleanarchitecturetest.R.layout.item_additional_profile_divider, container, true)
            }
            isFirstSection = false
            for (item in items) {
                addRow(inflater, container, item, openWebView)
            }
        }
    }

    private fun addRow(
        inflater: LayoutInflater,
        container: LinearLayout,
        item: AdditionalProfileItem,
        openWebView: (context: Context, title: String, url: String) -> Unit,
    ) {
        val row = inflater.inflate(com.example.cleanarchitecturetest.R.layout.item_additional_profile_row, container, false)
        val titleView = row.findViewById<TextView>(com.example.cleanarchitecturetest.R.id.additionalProfileRowTitle)
        titleView.text = item.title
        row.setOnClickListener {
            openWebView(container.context, item.webTitle, item.link)
        }
        container.addView(row)
    }
}
