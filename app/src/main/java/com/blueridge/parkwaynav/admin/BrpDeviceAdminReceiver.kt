package com.blueridge.parkwaynav.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Optional device-admin component. Some users want the app "protected" so it cannot be
 * uninstalled accidentally before they have backed up their settings. Enabling device admin
 * is entirely opt-in from Backup & Restore, and the in-app uninstall flow disables it first.
 */
class BrpDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}

object DeviceAdminHelper {
    fun component(context: Context) = ComponentName(context, BrpDeviceAdminReceiver::class.java)

    fun isActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(component(context))
    }

    /** Intent to prompt the user to enable device admin (must be started from an Activity). */
    fun enableIntent(context: Context, explanation: String): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        }

    /** Turns device admin off so the package can be removed. Safe to call when not active. */
    fun disable(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(component(context))) {
            runCatching { dpm.removeActiveAdmin(component(context)) }
        }
    }

    /**
     * In-app uninstall: first disables device admin (otherwise the OS blocks removal), then
     * launches the system uninstall dialog for this package.
     */
    fun uninstallSelf(context: Context) {
        disable(context)
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
