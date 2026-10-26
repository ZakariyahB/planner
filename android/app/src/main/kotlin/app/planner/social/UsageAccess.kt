package app.planner.social

import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's "usage access" is a special permission: it can't be requested in a dialog, the user turns it on
 * for the app in system settings, so the app checks it again whenever it comes back to the front.
 */
@Singleton
class UsageAccess @Inject constructor(@ApplicationContext private val context: Context) {
    fun isGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val uid = Process.myUid()
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            // Some devices report "default" until the permission itself is looked at.
            AppOpsManager.MODE_DEFAULT -> context.checkCallingOrSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    /** Opens the screen where the user turns usage access on. Returns false when the device has none. */
    fun openSettings(): Boolean {
        // First straight to this app's entry where the device supports it, then the plain list.
        val intents = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                // try the plainer intent
            }
        }
        return false
    }

    private companion object {
        const val PERMISSION = "android.permission.PACKAGE_USAGE_STATS"
    }
}
