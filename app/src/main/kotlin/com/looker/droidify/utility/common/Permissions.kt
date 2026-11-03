package com.looker.droidify.utility.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.looker.droidify.R
import com.looker.droidify.utility.isLocalHost
import com.looker.droidify.utility.common.extension.intent
import com.looker.droidify.utility.common.extension.powerManager

const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

fun Context.hasLocalNetworkPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= 37) {
        ContextCompat.checkSelfPermission(
            this,
            PERMISSION_ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

fun Fragment.registerLocalNetworkPermissionLauncher(
    onGranted: () -> Unit,
    onDenied: () -> Unit = {},
): ActivityResultLauncher<String> = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { isGranted ->
    if (isGranted) {
        onGranted()
    } else {
        context?.let {
            Toast.makeText(it, R.string.local_network_permission_required, Toast.LENGTH_SHORT).show()
        }
        onDenied()
    }
}

fun Fragment.checkLocalNetworkPermission(
    address: String,
    launcher: ActivityResultLauncher<String>,
): Boolean {
    if (isLocalHost(address) && !requireContext().hasLocalNetworkPermission()) {
        launcher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
        return false
    }
    return true
}

fun Context.isIgnoreBatteryEnabled() =
    powerManager?.isIgnoringBatteryOptimizations(packageName) == true

fun Context.requestBatteryFreedom() {
    if (!isIgnoreBatteryEnabled()) {
        val intent = intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            data = "package:$packageName".toUri()
        }
        runCatching {
            startActivity(intent)
        }
    }
}

fun Activity.requestNotificationPermission(
    request: (permission: String) -> Unit,
    onGranted: () -> Unit = {},
) {
    when {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED -> {
            onGranted()
        }

        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
            sdkAbove(Build.VERSION_CODES.TIRAMISU) {
                request(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        else -> {
            sdkAbove(Build.VERSION_CODES.TIRAMISU) {
                request(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
