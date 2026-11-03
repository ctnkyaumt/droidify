package com.looker.droidify.installer.installers.root

import android.content.Context
import com.looker.droidify.R
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.installers.Installer
import com.looker.droidify.installer.installers.isMagiskGranted
import com.looker.droidify.installer.installers.uninstallPackage
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.getPackageName
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.notifications.createInstallNotification
import com.looker.droidify.utility.notifications.installNotification
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class RootInstaller(private val context: Context) : Installer {

    override suspend fun install(
        installItem: InstallItem,
    ): InstallState {
        val hasRoot = runCatching { isMagiskGranted() }.getOrDefault(false)
        if (!hasRoot) {
            showFailedNotification(installItem, context.getString(R.string.error_root_not_available))
            return InstallState.Failed
        }

        return suspendCancellableCoroutine { cont ->
            try {
                val releaseFile = Cache.getReleaseFile(context, installItem.installFileName)
                if (!releaseFile.exists() || releaseFile.length() == 0L) {
                    showFailedNotification(installItem, context.getString(R.string.unknown_error_DESC))
                    if (cont.isActive) cont.resume(InstallState.Failed)
                    return@suspendCancellableCoroutine
                }

                val user = currentUser()
                val installCommand = INSTALL_COMMAND.format(
                    releaseFile.absolutePath,
                    user,
                    context.packageName,
                    releaseFile.length(),
                )

                Shell.cmd(installCommand).submit { shellResult ->
                    val result = if (shellResult.isSuccess) {
                        InstallState.Installed
                    } else {
                        val out = shellResult.out.joinToString("\n")
                        val isRootRevoked = !runCatching { isMagiskGranted() }.getOrDefault(false) ||
                            out.contains("Permission denied", ignoreCase = true)
                        val errorDesc = when {
                            isRootRevoked -> context.getString(R.string.error_root_not_available)
                            out.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) ->
                                context.getString(R.string.insufficient_storage_DESC)
                            out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                                out.contains("signatures do not match", ignoreCase = true) ->
                                context.getString(R.string.incompatible_signature_DESC)
                            out.isNotBlank() -> out
                            else -> context.getString(R.string.unknown_error_DESC)
                        }
                        showFailedNotification(installItem, errorDesc)
                        InstallState.Failed
                    }
                    if (cont.isActive) cont.resume(result)
                }
            } catch (e: Exception) {
                showFailedNotification(installItem, context.getString(R.string.error_root_not_available))
                if (cont.isActive) cont.resume(InstallState.Failed)
            }
        }
    }

    private fun showFailedNotification(installItem: InstallItem, errorDesc: String) {
        val appName = (context.packageManager.getPackageName(installItem.packageName.name)
            ?: installItem.packageName.name.substringAfterLast('.')).toString()
        val notification = context.createInstallNotification(
            appName = appName,
            state = InstallState.Failed,
        ) {
            setContentText(errorDesc)
        }
        context.notificationManager?.installNotification(
            packageName = installItem.packageName.name,
            notification = notification,
        )
    }

    override suspend fun uninstall(packageName: PackageName) =
        context.uninstallPackage(packageName)
}

private const val INSTALL_COMMAND = "cat %s | pm install --user %s -i %s -t -r -S %s"

/** Returns the current user of the device safely. */
private fun currentUser(): String = runCatching {
    val result = if (SdkCheck.isOreo) {
        Shell.cmd("am get-current-user").exec()
    } else {
        Shell.cmd("dumpsys activity | grep -E \"mUserLru\"").exec()
    }
    if (result.isSuccess && result.out.isNotEmpty()) {
        if (SdkCheck.isOreo) {
            result.out[0].trim()
        } else {
            result.out[0].trim().removePrefix("mUserLru: [").removeSuffix("]")
        }
    } else null
}.getOrNull()?.ifBlank { null } ?: "0"
