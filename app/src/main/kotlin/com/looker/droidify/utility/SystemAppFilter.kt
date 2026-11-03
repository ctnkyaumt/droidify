package com.looker.droidify.utility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import com.looker.droidify.utility.common.extension.getInstalledPackagesCompat

fun PackageInfo.isPreinstalledSystemApp(): Boolean {
    val flags = applicationInfo?.flags ?: 0
    return (flags and ApplicationInfo.FLAG_SYSTEM != 0) &&
        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0)
}

fun Context.getPreinstalledSystemPackages(): Set<String> {
    return packageManager.getInstalledPackagesCompat()
        ?.filter { it.isPreinstalledSystemApp() }
        ?.map { it.packageName }
        ?.toSet()
        .orEmpty()
}
