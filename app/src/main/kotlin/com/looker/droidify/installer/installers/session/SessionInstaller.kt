package com.looker.droidify.installer.installers.session

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.installer.installers.Installer
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.sdkAbove
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private class PendingSessionInstall(
    val installer: PackageInstaller,
    val callback: PackageInstaller.SessionCallback,
    val onResult: (Boolean) -> Unit,
)

class SessionInstaller(private val context: Context) : Installer {

    private val installer = context.packageManager.packageInstaller
    private val intent = Intent(context, SessionInstallerReceiver::class.java)

    companion object {
        private val flags = if (SdkCheck.isSnowCake) PendingIntent.FLAG_MUTABLE else 0

        // Committed sessions of this process still waiting for a result, by session id
        private val pendingSessions = ConcurrentHashMap<Int, PendingSessionInstall>()

        /**
         * Completes the install waiting on [sessionId]. Safe to call several times (session
         * callback, status broadcast): only the first call has an effect.
         */
        fun onSessionFinished(sessionId: Int, success: Boolean) {
            val pending = pendingSessions.remove(sessionId) ?: return
            pending.installer.unregisterSessionCallback(pending.callback)
            pending.onResult(success)
        }

        /**
         * Abandons [sessionId] and fails the install waiting on it.
         */
        fun abandon(sessionId: Int) {
            val pending = pendingSessions[sessionId] ?: return
            try {
                pending.installer.abandonSession(sessionId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            onSessionFinished(sessionId, false)
        }
    }

    override suspend fun install(
        installItem: InstallItem,
    ): InstallState = suspendCancellableCoroutine { cont ->
        val cacheFile = Cache.getReleaseFile(context, installItem.installFileName)
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            cont.resume(InstallState.Failed)
            return@suspendCancellableCoroutine
        }
        val id = installer.createSession(sessionParams(installItem))

        val callback = object : PackageInstaller.SessionCallback() {
            override fun onCreated(sessionId: Int) {}
            override fun onBadgingChanged(sessionId: Int) {}
            override fun onActiveChanged(sessionId: Int, active: Boolean) {}
            override fun onProgressChanged(sessionId: Int, progress: Float) {}
            override fun onFinished(sessionId: Int, success: Boolean) {
                if (sessionId == id) onSessionFinished(id, success)
            }
        }

        cont.invokeOnCancellation {
            pendingSessions.remove(id)
            installer.unregisterSessionCallback(callback)
            try {
                installer.abandonSession(id)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }

        pendingSessions[id] = PendingSessionInstall(installer, callback) { success ->
            if (cont.isActive) {
                cont.resume(if (success) InstallState.Installed else InstallState.Failed)
            }
        }
        installer.registerSessionCallback(callback, Handler(Looper.getMainLooper()))

        try {
            val session = installer.openSession(id)

            session.use { activeSession ->
                val sizeBytes = cacheFile.length()
                cacheFile.inputStream().use { fileStream ->
                    activeSession.openWrite(cacheFile.name, 0, sizeBytes).use { outputStream ->
                        if (cont.isActive) {
                            fileStream.copyTo(outputStream)
                            activeSession.fsync(outputStream)
                        }
                    }
                }

                val pendingIntent = PendingIntent.getBroadcast(context, id, intent, flags)

                if (cont.isActive) activeSession.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            pendingSessions.remove(id)
            installer.unregisterSessionCallback(callback)
            try {
                installer.abandonSession(id)
            } catch (abandonError: Exception) {
                abandonError.printStackTrace()
            }
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun uninstall(packageName: PackageName) =
        suspendCancellableCoroutine { cont ->
            intent.putExtra(SessionInstallerReceiver.ACTION_UNINSTALL, true)
            val pendingIntent = PendingIntent.getBroadcast(context, -1, intent, flags)

            installer.uninstall(packageName.name, pendingIntent.intentSender)
            cont.resume(Unit)
        }

    private fun sessionParams(installItem: InstallItem) =
        PackageInstaller.SessionParams(MODE_FULL_INSTALL).apply {
            sdkAbove(sdk = Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sdkAbove(sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setRequestUpdateOwnership(true)
            }
            sdkAbove(sdk = Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                val unarchiveId = installItem.unarchiveId
                if (unarchiveId != null) {
                    setUnarchiveId(unarchiveId)
                }
            }
        }
}
