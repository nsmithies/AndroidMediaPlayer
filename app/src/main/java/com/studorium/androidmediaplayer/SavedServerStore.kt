package com.studorium.androidmediaplayer

import android.content.Context

class SavedServerStore(
    context: Context
) {

    private val preferences =
        context.applicationContext
            .getSharedPreferences(
                "music_player_settings",
                Context.MODE_PRIVATE
            )

    fun saveServer(
        server: DlnaServer
    ) {

        preferences
            .edit()
            .putString(
                KEY_FRIENDLY_NAME,
                server.friendlyName
            )
            .putString(
                KEY_MANUFACTURER,
                server.manufacturer
            )
            .putString(
                KEY_MODEL_NAME,
                server.modelName
            )
            .putString(
                KEY_LOCATION,
                server.location
            )
            .putString(
                KEY_CONTROL_URL,
                server.controlUrl
            )
            .apply()
    }

    fun loadServer(): DlnaServer? {

        val friendlyName =
            preferences.getString(
                KEY_FRIENDLY_NAME,
                null
            )
                ?: return null

        val location =
            preferences.getString(
                KEY_LOCATION,
                null
            )
                ?: return null

        val controlUrl =
            preferences.getString(
                KEY_CONTROL_URL,
                null
            )
                ?: return null

        val manufacturer =
            preferences.getString(
                KEY_MANUFACTURER,
                ""
            )
                ?: ""

        val modelName =
            preferences.getString(
                KEY_MODEL_NAME,
                ""
            )
                ?: ""

        return DlnaServer(
            friendlyName =
                friendlyName,
            manufacturer =
                manufacturer,
            modelName =
                modelName,
            location =
                location,
            controlUrl =
                controlUrl
        )
    }

    fun clearServer() {

        preferences
            .edit()
            .remove(
                KEY_FRIENDLY_NAME
            )
            .remove(
                KEY_MANUFACTURER
            )
            .remove(
                KEY_MODEL_NAME
            )
            .remove(
                KEY_LOCATION
            )
            .remove(
                KEY_CONTROL_URL
            )
            .apply()
    }

    fun hasSavedServer(): Boolean {

        return preferences.contains(
            KEY_CONTROL_URL
        )
    }

    companion object {

        private const val KEY_FRIENDLY_NAME =
            "server_friendly_name"

        private const val KEY_MANUFACTURER =
            "server_manufacturer"

        private const val KEY_MODEL_NAME =
            "server_model_name"

        private const val KEY_LOCATION =
            "server_location"

        private const val KEY_CONTROL_URL =
            "server_control_url"
    }
}