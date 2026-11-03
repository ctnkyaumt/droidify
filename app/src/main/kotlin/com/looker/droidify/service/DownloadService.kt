package com.looker.droidify.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.looker.droidify.BuildConfig
import com.looker.droidify.MainActivity
import com.looker.droidify.R.string.connection_error_DESC
import com.looker.droidify.R.string.could_not_download_FORMAT
import com.looker.droidify.R.string.could_not_validate_FORMAT
import com.looker.droidify.R.string.http_error_DESC
import com.looker.droidify.R.string.io_error_DESC
import com.looker.droidify.R.string.socket_error_DESC
import com.looker.droidify.R.string.unknown_error_DESC
import com.looker.droidify.data.model.toPackageName
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.model.Release
import com.looker.droidify.model.Repository
import com.looker.droidify.network.DataSize
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.network.header.authentication
import com.looker.droidify.network.percentBy
import com.looker.droidify.network.validation.ValidationResult
import com.looker.droidify.utility.common.Constants
import com.looker.droidify.utility.common.Constants.NOTIFICATION_CHANNEL_INSTALL
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.createNotificationChannel
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.common.extension.startForegroundSafe
import com.looker.droidify.utility.common.extension.startServiceCompat
import com.looker.droidify.utility.common.extension.stopForegroundCompat
import com.looker.droidify.utility.common.extension.toPendingIntent
import com.looker.droidify.utility.common.extension.updateAsMutable
import com.looker.droidify.utility.common.log
import com.looker.droidify.utility.notifications.createInstallNotification
import com.looker.droidify.utility.notifications.installNotification
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import com.looker.droidify.R.string as stringRes

@AndroidEntryPoint
class DownloadService : ConnectionService<DownloadService.Binder>() {
    companion object {
        private const val ACTION_CANCEL = "${BuildConfig.APPLICATION_ID}.intent.action.CANCEL"
    }

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var downloader: Downloader

    private val installerType
        get() = settingsRepository.get { installerType }

    @Inject
    lateinit var installer: InstallManager

    sealed class State(val packageName: String) {
        data object Idle : State("")
        data class Connecting(val name: String) : State(name)
        data class Downloading(val name: String, val read: DataSize, val total: DataSize?) : State(
            name,
        )

        data class Error(val name: String) : State(name)
        data class Cancel(val name: String) : State(name)
        data class Success(val name: String, val release: Release) : State(name)
    }

    data class DownloadState(
        val currentItem: State = State.Idle,
        val queue: List<String> = emptyList(),
    ) {
        infix fun isDownloading(packageName: String): Boolean =
            currentItem.packageName == packageName && (
                currentItem is State.Connecting || currentItem is State.Downloading
                )

        infix fun isComplete(packageName: String): Boolean =
            currentItem.packageName == packageName && (
                currentItem is State.Error ||
                    currentItem is State.Cancel ||
                    currentItem is State.Success ||
                    currentItem is State.Idle
                )
    }

    private val _downloadState = MutableStateFlow(DownloadState())

    private typealias Task = DownloadTask

    private data class CurrentTask(val task: Task, val lastState: State, val job: Job? = null)

    private var started = false
    private var foregroundFailed = false
    private val downloadQueue = DownloadQueue()
    private var currentTask: CurrentTask? = null

    private val lock = Mutex()

    inner class Binder : android.os.Binder() {
        val downloadState = _downloadState.asStateFlow()
        fun enqueue(
            packageName: String,
            name: String,
            repository: Repository,
            release: Release,
            isUpdate: Boolean = false,
        ) {
            val task = DownloadTask(
                packageName = packageName,
                name = name,
                repository = repository,
                release = release,
                isUpdate = isUpdate,
            )
            if (Cache.getReleaseFile(this@DownloadService, release.cacheFileName).exists()) {
                lifecycleScope.launch { publishSuccess(task) }
                return
            }
            if (!downloadQueue.enqueue(task)) {
                // Already queued or downloading; ignore duplicate
                return
            }
            notificationManager?.cancel(
                task.notificationTag,
                Constants.NOTIFICATION_ID_DOWNLOADING,
            )
            if (currentTask == null) {
                handleDownload()
            } else {
                updateCurrentQueue { add(packageName) }
            }
        }

        fun cancel(packageName: String) {
            cancelTasks(packageName)
            cancelCurrentTask(packageName)
        }
    }

    private val binder = Binder()
    override fun onBind(intent: Intent): Binder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(
            id = Constants.NOTIFICATION_CHANNEL_DOWNLOADING,
            name = getString(stringRes.downloading),
        )
        createNotificationChannel(
            id = NOTIFICATION_CHANNEL_INSTALL,
            name = getString(stringRes.install),
        )

        lifecycleScope.launch {
            _downloadState
                .filter { currentTask != null }
                .sample(400)
                .collectLatest {
                    publishForegroundState(false, it.currentItem)
                }
        }
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        onDestroy()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTasks(null)
        cancelCurrentTask(null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            currentTask?.let { binder.cancel(it.task.packageName) }
            stopSelf()
        } else {
            val promoted = startForegroundSafe(
                id = Constants.NOTIFICATION_ID_DOWNLOADING,
                notification = stateNotificationBuilder
                    .setContentTitle(getString(stringRes.downloading_FORMAT, ""))
                    .setContentText(getString(stringRes.connecting))
                    .setProgress(1, 0, true)
                    .build(),
            )
            if (!promoted) {
                handleForegroundDenied()
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun cancelTasks(packageName: String?) {
        val removed = downloadQueue.cancel(packageName)
        if (removed && packageName != null) {
            if (currentTask == null) {
                updateCurrentState(State.Cancel(packageName))
            }
        } else if (removed && packageName == null) {
            if (currentTask == null) {
                updateCurrentState(State.Idle)
            }
        }
    }

    private fun cancelCurrentTask(packageName: String?) {
        currentTask?.let {
            if (packageName == null || it.task.packageName == packageName) {
                it.job?.cancel()
                downloadQueue.completeActive(it.task.packageName)
                currentTask = null
                updateCurrentState(State.Cancel(it.task.packageName))
            }
        }
    }

    private fun showErrorNotification(
        task: Task,
        titleRes: Int,
        description: String?,
    ) {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData("package:${task.packageName}".toUri())
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .toPendingIntent(this)
        notificationManager?.notify(
            task.notificationTag,
            Constants.NOTIFICATION_ID_DOWNLOADING,
            NotificationCompat
                .Builder(this, Constants.NOTIFICATION_CHANNEL_DOWNLOADING)
                .setAutoCancel(true)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setColor(Color.GREEN)
                .setOnlyAlertOnce(true)
                .setContentIntent(intent)
                .setContentTitle(getString(titleRes, task.name))
                .setContentText(description)
                .build(),
        )
    }

    private fun showNotificationInstall(task: Task) {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_INSTALL)
            .setData("package:${task.packageName}".toUri())
            .putExtra(MainActivity.EXTRA_CACHE_FILE_NAME, task.release.cacheFileName)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .toPendingIntent(this)
        val notification = createInstallNotification(
            appName = task.name,
            state = InstallState.Pending,
            autoCancel = true,
        ) {
            setContentIntent(intent)
        }
        notificationManager?.installNotification(
            packageName = task.packageName,
            notification = notification,
        )
    }

    private suspend fun publishSuccess(task: Task) {
        val currentInstaller = installerType.first()
        updateCurrentQueue { add("") }
        updateCurrentState(State.Success(task.packageName, task.release))
        val autoInstallWithSessionInstaller =
            SdkCheck.canAutoInstall(task.release.targetSdkVersion) &&
                currentInstaller == InstallerType.SESSION &&
                task.isUpdate

        showNotificationInstall(task)
        if (currentInstaller == InstallerType.ROOT ||
            currentInstaller == InstallerType.SHIZUKU ||
            currentInstaller == InstallerType.SESSION ||
            autoInstallWithSessionInstaller
        ) {
            val installItem = InstallItem(
                packageName = task.packageName.toPackageName(),
                installFileName = task.release.cacheFileName,
                versionCode = task.release.versionCode,
            )
            installer install installItem
        }
    }

    private val stateNotificationBuilder by lazy {
        NotificationCompat
            .Builder(this, Constants.NOTIFICATION_CHANNEL_DOWNLOADING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setColor(Color.GREEN)
            .addAction(
                0,
                getString(stringRes.cancel),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, this::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
    }

    private fun publishForegroundState(force: Boolean, state: State) {
        if (foregroundFailed) return
        if (!force && currentTask == null) return
        currentTask = currentTask!!.copy(lastState = state)
        val notification = stateNotificationBuilder.downloadingNotificationContent(state)
        if (notification == null) {
            log("Invalid Download State: $state", "DownloadService", Log.ERROR)
            return
        }

        val promoted = startForegroundSafe(
            id = Constants.NOTIFICATION_ID_DOWNLOADING,
            notification = notification.build(),
        )
        if (!promoted) handleForegroundDenied()
    }

    private fun handleForegroundDenied() {
        if (foregroundFailed) return
        foregroundFailed = true
        notifyUpdatesPending()
        cancelTasks(null)
        cancelCurrentTask(null)
        stopForegroundCompat()
    }

    private fun notifyUpdatesPending() {
        notificationManager?.notify(
            Constants.NOTIFICATION_ID_DOWNLOADING,
            NotificationCompat
                .Builder(this, Constants.NOTIFICATION_CHANNEL_DOWNLOADING)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setColor(Color.GREEN)
                .setAutoCancel(true)
                .setContentTitle(getString(stringRes.updates))
                .setContentText(getString(stringRes.background_updates_restricted_DESC))
                .setContentIntent(
                    Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_UPDATES)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .toPendingIntent(this),
                )
                .build(),
        )
    }

    private fun NotificationCompat.Builder.downloadingNotificationContent(
        state: State,
    ): NotificationCompat.Builder? {
        return when (state) {
            is State.Connecting -> {
                setContentTitle(getString(stringRes.downloading_FORMAT, currentTask!!.task.name))
                setContentText(getString(stringRes.connecting))
                setProgress(1, 0, true)
            }

            is State.Downloading -> {
                setContentTitle(getString(stringRes.downloading_FORMAT, currentTask!!.task.name))
                if (state.total != null) {
                    setContentText("${state.read} / ${state.total}")
                    setProgress(100, state.read.value percentBy state.total.value, false)
                } else {
                    setContentText(state.read.toString())
                    setProgress(0, 0, true)
                }
            }

            else -> null
        }
    }

    private fun handleDownload() {
        if (currentTask != null) return
        if (!started) {
            started = true
            startServiceCompat()
        }
        val task = downloadQueue.poll()
        if (task == null) {
            if (started) started = false
            stopForegroundCompat()
            return
        }
        val connectionState = State.Connecting(task.packageName)
        currentTask = CurrentTask(task, connectionState)
        with(stateNotificationBuilder) {
            setWhen(System.currentTimeMillis())
            setContentIntent(createNotificationIntent(task.packageName))
        }
        publishForegroundState(true, connectionState)
        val partialReleaseFile = Cache.getPartialReleaseFile(this, task.release.cacheFileName)
        val job = lifecycleScope.downloadFile(task, partialReleaseFile)
        currentTask = currentTask?.copy(job = job)
        updateCurrentState(State.Connecting(task.packageName))
    }

    private fun createNotificationIntent(packageName: String): PendingIntent? =
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData("package:$packageName".toUri())
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .toPendingIntent(this)

    private fun CoroutineScope.downloadFile(
        task: Task,
        target: File,
    ) = launch {
        try {
            if (Cache.getEmptySpace(this@DownloadService) < task.release.size) {
                updateCurrentState(State.Error(task.packageName))
                showErrorNotification(
                    task,
                    stringRes.insufficient_storage,
                    getString(stringRes.insufficient_storage_DESC),
                )
                return@launch
            }
            val response = downloader.downloadToFile(
                url = task.url,
                target = target,
                headers = { if (task.authentication.isNotEmpty()) authentication(task.authentication) },
            ) { read, total ->
                yield()
                updateCurrentState(State.Downloading(task.packageName, read, total))
            }

            when (response) {
                is NetworkResponse.Success -> {
                    val releaseValidator = ReleaseFileValidator(
                        context = this@DownloadService,
                        packageName = task.packageName,
                        release = task.release,
                    )
                    val result = releaseValidator.validate(target)
                    if (result is ValidationResult.Invalid) {
                        target.delete()
                        updateCurrentState(State.Error(task.packageName))
                        showErrorNotification(task, could_not_validate_FORMAT, result.message)
                        return@launch
                    }
                    val releaseFile = Cache.getReleaseFile(
                        this@DownloadService,
                        task.release.cacheFileName,
                    )
                    target.renameTo(releaseFile)
                    publishSuccess(task)
                }

                is NetworkResponse.Error -> {
                    updateCurrentState(State.Error(task.packageName))
                    val description = when (response) {
                        is NetworkResponse.Error.ConnectionTimeout -> getString(connection_error_DESC)

                        is NetworkResponse.Error.Http -> {
                            target.delete()
                            getString(http_error_DESC, "HTTP ${response.statusCode}")
                        }

                        is NetworkResponse.Error.IO -> getString(io_error_DESC)
                        is NetworkResponse.Error.SocketTimeout -> getString(socket_error_DESC)
                        is NetworkResponse.Error.Unknown -> getString(unknown_error_DESC)
                    }
                    showErrorNotification(task, could_not_download_FORMAT, description)
                }
            }
        } finally {
            downloadQueue.completeActive(task.packageName)
            lock.withLock { currentTask = null }
            handleDownload()
        }
    }

    private fun updateCurrentState(state: State) {
        _downloadState.update {
            val newQueue =
                if (state.packageName in it.queue) {
                    it.queue.updateAsMutable {
                        removeAll { name -> name == "" }
                        remove(state.packageName)
                    }
                } else {
                    it.queue
                }
            it.copy(currentItem = state, queue = newQueue)
        }
    }

    private fun updateCurrentQueue(block: MutableList<String>.() -> Unit) {
        _downloadState.update { state ->
            state.copy(queue = state.queue.updateAsMutable(block))
        }
    }
}
