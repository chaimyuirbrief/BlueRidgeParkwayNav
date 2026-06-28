package com.blueridge.parkwaynav

import android.app.Application
import com.blueridge.parkwaynav.nav.NavigationService
import com.google.android.libraries.places.api.Places

class BrpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NavigationService.ensureChannel(this)
        val key = BuildConfig.MAPS_API_KEY
        if (key.isNotBlank() && !Places.isInitialized()) {
            runCatching { Places.initialize(applicationContext, key) }
        }
    }
}
