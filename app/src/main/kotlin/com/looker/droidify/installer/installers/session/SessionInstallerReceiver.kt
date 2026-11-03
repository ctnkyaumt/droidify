package com.looker.droidify.installer.installers.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.looker.droidify.R
import com.looker.droidify.data.model.toPackageName
import com.looker.droidify.database.Database
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.Constants.NOTIFICATION_CHANNEL_INSTALL
import com.looker.droidify.utility.common.createNotificationChannel
import com.looker.droidify.utility.common.extension.getPackageName
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.getParcelableCompat
import com.looker.droidify.utility.getParcelableExtraCompat
import com.looker.droidify.utility.notifications.createInstallNotification
import com.looker.droidify.utility.notifications.installNotification
import com.looker.droidify.utility.notifications.removeInstallNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SessionInstallerReceiver : BroadcastReceiver() {

    // This is a cyclic dependency injection, I know but this is the best option for now
    @Inject
    lateinit var installManager: InstallManager

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val isUninstall = intent.getBooleanExtra(ACTION_UNINSTALL, false)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // prompts user to enable unknown source
            val promptIntent: Intent? = intent.getParcelableExtraCompat(Intent.EXTRA_INTENT)

            if (promptIntent == null) {
                // Nothing to show, the session would wait forever
                if (!isUninstall && sessionId != -1) SessionInstaller.abandon(sessionId)
                return
            }
            promptIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            promptIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
            promptIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                context.startActivity(promptIntent)
            } catch (e: Exception) {
                e.printStackTrace()
                if (!isUninstall && sessionId != -1) SessionInstaller.abandon(sessionId)
            }
        } else {
            notifyStatus(intent, context)
            // Finishes the waiting install even if the session callback is never delivered
            if (!isUninstall && sessionId != -1) {
                SessionInstaller.onSessionFinished(
                    sessionId = sessionId,
                    success = status == PackageInstaller.STATUS_SUCCESS,
                )
            }
        }
    }

    private fun notifyStatus(intent: Intent, context: Context) {
        val packageManager = context.packageManager
        val notificationManager = context.notificationManager

        context.createNotificationChannel(
            id = NOTIFICATION_CHANNEL_INSTALL,
            name = context.getString(R.string.install),
        )

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val isUninstall = intent.getBooleanExtra(ACTION_UNINSTALL, false)

        val appName = packageManager.getPackageName(packageName)

        if (packageName != null) {
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    if (isUninstall) {
                        Database.InstalledAdapter.delete(packageName)
                    }
                    notificationManager?.removeInstallNotification(packageName)
                    val notification = context.createInstallNotification(
                        appName = (appName ?: packageName.substringAfterLast('.')).toString(),
                        state = InstallState.Installed,
                        isUninstall = isUninstall,
                    ) {
                        setTimeoutAfter(SUCCESS_TIMEOUT)
                    }
                    notificationManager?.installNotification(
                        packageName = packageName,
                        notification = notification,
                    )
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    notificationManager?.removeInstallNotification(packageName)
                    installManager.setFailed(packageName.toPackageName())
                }

                else -> {
                    if (isUninstall) {
                        installManager.remove(packageName.toPackageName())
                    } else {
                        installManager.setFailed(packageName.toPackageName())
                    }
                    val errorDesc = when (status) {
                        PackageInstaller.STATUS_FAILURE_STORAGE ->
                            context.getString(R.string.insufficient_storage_DESC)
                        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                        PackageInstaller.STATUS_FAILURE_CONFLICT ->
                            context.getString(R.string.incompatible_signature_DESC)
                        else -> message ?: context.getString(R.string.unknown_error_DESC)
                    }
                    val displayName = (appName ?: packageName.substringAfterLast('.')).toString()
                    val notification = context.createInstallNotification(
                        appName = displayName,
                        state = InstallState.Failed,
                        isUninstall = isUninstall,
                    ) {
                        setContentText(errorDesc)
                    }
                    notificationManager?.installNotification(
                        packageName = packageName,
                        notification = notification,
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_UNINSTALL = "action_uninstall"

        private const val SUCCESS_TIMEOUT = 5_000L
    }
}
