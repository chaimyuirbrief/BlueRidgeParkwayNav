package com.blueridge.parkwaynav.di

import android.content.Context
import com.blueridge.parkwaynav.BuildConfig
import com.blueridge.parkwaynav.data.BackupManager
import com.blueridge.parkwaynav.data.BrpRepository
import com.blueridge.parkwaynav.data.SavedRoutesRepository
import com.blueridge.parkwaynav.data.SettingsRepository
import com.blueridge.parkwaynav.nav.LocationEngine
import com.blueridge.parkwaynav.places.PlacesHelper
import com.blueridge.parkwaynav.routing.BrpRouter
import com.blueridge.parkwaynav.routing.DirectionsService

/** Minimal manual DI: lazily builds and caches the app's singletons from an app context. */
class AppContainer private constructor(context: Context) {
    private val appContext = context.applicationContext

    val brp: BrpRepository by lazy { BrpRepository.get(appContext) }
    val settingsRepo: SettingsRepository by lazy { SettingsRepository(appContext) }
    val routesRepo: SavedRoutesRepository by lazy { SavedRoutesRepository.get(appContext) }
    val directions: DirectionsService by lazy { DirectionsService(BuildConfig.MAPS_API_KEY) }
    val router: BrpRouter by lazy { BrpRouter(brp, directions) }
    val placesHelper: PlacesHelper by lazy { PlacesHelper(appContext, brp) }
    val locationEngine: LocationEngine by lazy { LocationEngine(appContext) }
    val backupManager: BackupManager by lazy { BackupManager(appContext, settingsRepo, routesRepo) }

    companion object {
        @Volatile private var instance: AppContainer? = null
        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context).also { instance = it }
            }
    }
}
