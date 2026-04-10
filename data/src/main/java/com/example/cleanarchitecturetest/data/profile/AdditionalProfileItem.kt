package com.example.cleanarchitecturetest.data.profile

/**
 * One row in [AdditionalProfileItemsParser]: list title in the profile, WebView toolbar title, URL.
 */
data class AdditionalProfileItem(
    val title: String,
    val webTitle: String,
    val link: String,
)
