package com.studorium.androidmediaplayer

import android.content.Context

class AppSettingsStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var cacheArtwork: Boolean
        get() = preferences.getBoolean(KEY_CACHE_ARTWORK, true)
        set(value) = preferences.edit().putBoolean(KEY_CACHE_ARTWORK, value).apply()

    var lookupMissingArtwork: Boolean
        get() = preferences.getBoolean(KEY_LOOKUP_MISSING_ARTWORK, false)
        set(value) = preferences.edit().putBoolean(KEY_LOOKUP_MISSING_ARTWORK, value).apply()

    var artworkWifiOnly: Boolean
        get() = preferences.getBoolean(KEY_ARTWORK_WIFI_ONLY, true)
        set(value) = preferences.edit().putBoolean(KEY_ARTWORK_WIFI_ONLY, value).apply()

    companion object {
        private const val KEY_CACHE_ARTWORK = "cache_artwork"
        private const val KEY_LOOKUP_MISSING_ARTWORK = "lookup_missing_artwork"
        private const val KEY_ARTWORK_WIFI_ONLY = "artwork_wifi_only"
    }
}
