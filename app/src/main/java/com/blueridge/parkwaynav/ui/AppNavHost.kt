package com.blueridge.parkwaynav.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.blueridge.parkwaynav.ui.about.AboutScreen
import com.blueridge.parkwaynav.ui.home.HomeScreen
import com.blueridge.parkwaynav.ui.nav.NavigationScreen
import com.blueridge.parkwaynav.ui.planner.RoutePlannerScreen
import com.blueridge.parkwaynav.ui.preview.RoutePreviewScreen
import com.blueridge.parkwaynav.ui.pois.OverlooksScreen
import com.blueridge.parkwaynav.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow

/** Holds a pending saved-route id from a home-screen shortcut deep link (brpnav://route?id=). */
object PendingDeepLink {
    val routeId = MutableStateFlow<String?>(null)
}

object Routes {
    const val HOME = "home"
    const val PLANNER = "planner"
    const val PREVIEW = "preview"
    const val NAVIGATION = "navigation"
    const val OVERLOOKS = "overlooks"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}

@Composable
fun AppNavHost(app: MainViewModel) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Surface transient status messages (route saved, backup result, …) as toasts.
    val message by app.message.collectAsState()
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            app.consumeMessage()
        }
    }

    // Launch a saved route directly when opened from a home-screen shortcut.
    LaunchedEffect(Unit) {
        PendingDeepLink.routeId.collect { id ->
            if (id != null) {
                app.savedRoutes.value.firstOrNull { it.id == id }?.let { saved ->
                    app.loadSavedRoute(saved)
                    app.computeRoute { ok -> if (ok) navController.navigate(Routes.PREVIEW) }
                }
                PendingDeepLink.routeId.value = null
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(app, navController) }
        composable(Routes.PLANNER) { RoutePlannerScreen(app, navController) }
        composable(Routes.PREVIEW) { RoutePreviewScreen(app, navController) }
        composable(Routes.NAVIGATION) { NavigationScreen(navController) }
        composable(Routes.OVERLOOKS) { OverlooksScreen(app, navController) }
        composable(Routes.SETTINGS) { SettingsScreen(app, navController) }
        composable(Routes.ABOUT) { AboutScreen(navController) }
    }
}
