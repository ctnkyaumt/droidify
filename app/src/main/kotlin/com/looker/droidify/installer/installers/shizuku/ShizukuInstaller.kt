package com.looker.droidify.installer.installers.shizuku

import android.content.Context
import com.looker.droidify.R
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.installers.Installer
import com.looker.droidify.installer.installers.isShizukuAlive
import com.looker.droidify.installer.installers.isShizukuGranted
import com.looker.droidify.installer.installers.uninstallPackage
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.getPackageName
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.notifications.createInstallNotification
import com.looker.droidify.utility.notifications.installNotification
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.InputStream
import kotlin.coroutines.resume

class ShizukuInstaller(private val context: Context) : Installer {

    companion object {
        private val SESSION_ID_REGEX = Regex("(?<=\\[).+?(?=])")
    }

    override suspend fun install(
        installItem: InstallItem,
    ): InstallState {
        if (!runCatching { isShizukuAlive() }.getOrDefault(false)) {
            showFailedNotification(installItem, context.getString(R.string.error_shizuku_not_running_DESC))
            return InstallState.Failed
        }
        if (!runCatching { isShizukuGranted() }.getOrDefault(false)) {
            showFailedNotification(installItem, context.getString(R.string.error_shizuku_not_granted_DESC))
            return InstallState.Failed
        }

        return suspendCancellableCoroutine { cont ->
            var sessionId: String? = null
            val file = Cache.getReleaseFile(context, installItem.installFileName)
            try {
                val fileSize = file.length()
                if (fileSize == 0L) {
                    showFailedNotification(installItem, context.getString(R.string.unknown_error_DESC))
                    if (cont.isActive) cont.resume(InstallState.Failed)
                    return@suspendCancellableCoroutine
                }
                if (cont.isCompleted) return@suspendCancellableCoroutine
                val installerPackage = context.packageName
                file.inputStream().use {
                    val createCommand =
                        if (SdkCheck.isNougat) {
                            "pm install-create --user current -i $installerPackage -S $fileSize"
                        } else {
                            "pm install-create -i $installerPackage -S $fileSize"
                        }
                    val createResult = exec(createCommand)
                    sessionId = SESSION_ID_REGEX.find(createResult.out)?.value
                    if (sessionId == null) {
                        val out = createResult.out
                        val isPermissionError = out.contains("SecurityException", ignoreCase = true) ||
                            !runCatching { isShizukuGranted() }.getOrDefault(false)
                        val errorDesc = if (isPermissionError) {
                            context.getString(R.string.error_shizuku_not_granted_DESC)
                        } else {
                            out.ifBlank { context.getString(R.string.unknown_error_DESC) }
                        }
                        showFailedNotification(installItem, errorDesc)
                        if (cont.isActive) cont.resume(InstallState.Failed)
                        return@suspendCancellableCoroutine
                    }
                    if (cont.isCompleted) return@suspendCancellableCoroutine

                    val writeResult = exec("pm install-write -S $fileSize $sessionId base -", it)
                    if (writeResult.resultCode != 0) {
                        showFailedNotification(installItem, context.getString(R.string.unknown_error_DESC))
                        runCatching { exec("pm install-abandon $sessionId") }
                        if (cont.isActive) cont.resume(InstallState.Failed)
                        return@suspendCancellableCoroutine
                    }
                    if (cont.isCompleted) return@suspendCancellableCoroutine

                    val commitResult = exec("pm install-commit $sessionId")
                    if (commitResult.resultCode != 0) {
                        val out = commitResult.out
                        val errorDesc = when {
                            out.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE", ignoreCase = true) ->
                                context.getString(R.string.insufficient_storage_DESC)
                            out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                                out.contains("signatures do not match", ignoreCase = true) ->
                                context.getString(R.string.incompatible_signature_DESC)
                            out.isNotBlank() -> out
                            else -> context.getString(R.string.unknown_error_DESC)
                        }
                        showFailedNotification(installItem, errorDesc)
                        runCatching { exec("pm install-abandon $sessionId") }
                        if (cont.isActive) cont.resume(InstallState.Failed)
                        return@suspendCancellableCoroutine
                    }
                    if (cont.isCompleted) return@suspendCancellableCoroutine
                    cont.resume(InstallState.Installed)
                }
            } catch (_: Exception) {
                if (sessionId != null) runCatching { exec("pm install-abandon $sessionId") }
                val isAlive = runCatching { isShizukuAlive() }.getOrDefault(false)
                val isGranted = runCatching { isShizukuGranted() }.getOrDefault(false)
                val errorDesc = when {
                    !isAlive -> context.getString(R.string.error_shizuku_not_running_DESC)
                    !isGranted -> context.getString(R.string.error_shizuku_not_granted_DESC)
                    else -> context.getString(R.string.unknown_error_DESC)
                }
                showFailedNotification(installItem, errorDesc)
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

    private data class ShellResult(val resultCode: Int, val out: String)

    private fun exec(command: String, stdin: InputStream? = null): ShellResult {
        val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        if (stdin != null) {
            process.outputStream.use { stdin.copyTo(it) }
        }
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val resultCode = process.waitFor()
        return ShellResult(resultCode, output)
    }
}
