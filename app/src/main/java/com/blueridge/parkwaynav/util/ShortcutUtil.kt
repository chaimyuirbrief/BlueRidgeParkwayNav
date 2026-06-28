package com.blueridge.parkwaynav.util

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import com.blueridge.parkwaynav.R

/** Creates pinned home-screen shortcuts that deep-link straight into a saved route. */
object ShortcutUtil {
    fun pinRoute(context: Context, routeId: String, name: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return
        if (!sm.isRequestPinShortcutSupported) return

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("brpnav://route?id=$routeId")).apply {
            setPackage(context.packageName)
        }
        val shortcut = ShortcutInfo.Builder(context, "route_$routeId")
            .setShortLabel(name.take(20))
            .setLongLabel(name.take(40))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
            .setIntent(intent)
            .build()
        runCatching { sm.requestPinShortcut(shortcut, null) }
    }
}
