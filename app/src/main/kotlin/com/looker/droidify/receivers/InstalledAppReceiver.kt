package com.looker.droidify.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.looker.droidify.database.Database
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.extension.toInstalledItem

class InstalledAppReceiver(
    private val packageManager: PackageManager,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName =
            intent.data?.let { if (it.scheme == "package") it.schemeSpecificPart else null }
        if (packageName != null) {
            val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            when (intent.action.orEmpty()) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    val packageInfo = packageManager.getPackageInfoCompat(packageName)
                    if (packageInfo != null) {
                        Database.InstalledAdapter.put(packageInfo.toInstalledItem())
                    }
                }

                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_FULLY_REMOVED,
                -> {
                    if (!isReplacing) {
                        Database.InstalledAdapter.delete(packageName)
                    }
                }
            }
        }
    }
}
