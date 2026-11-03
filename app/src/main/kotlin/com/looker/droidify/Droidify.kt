package com.looker.droidify

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.looker.droidify.datastore.model.AutoSync
import com.looker.droidify.work.SyncWorker
import kotlinx.coroutines.flow.combine
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import com.looker.droidify.content.ProductPreferences
import com.looker.droidify.database.Database
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.index.RepositoryUpdater
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.network.Downloader
import com.looker.droidify.receivers.InstalledAppReceiver
import com.looker.droidify.service.FailedUpdateTracker
import com.looker.droidify.service.SyncService
import com.looker.droidify.utility.DateFormatter
import com.looker.droidify.utility.NotificationIconHelper
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.getDrawableCompat
import com.looker.droidify.utility.common.extension.getInstalledPackagesCompat
import com.looker.droidify.utility.extension.toInstalledItem
import com.looker.droidify.work.CleanUpWorker
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.INFINITE

@HiltAndroidApp
class Droidify : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    private val parentJob = SupervisorJob()
    private val appScope = CoroutineScope(Dispatchers.Default + parentJob)

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var installer: InstallManager

    @Inject
    lateinit var downloader: Downloader

    @Inject
    lateinit var httpClient: OkHttpClient

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        val databaseUpdated = Database.init(this)
        FailedUpdateTracker.init(this)
        ProductPreferences.init(this, appScope)
        RepositoryUpdater.init(appScope, downloader)
        listenApplications()
        checkLanguage()
        updatePreference()
        appScope.launch { installer() }
        if (databaseUpdated) {
            appScope.launch {
                SyncService.forceSyncAll(applicationContext)
            }
        }
    }

    private fun listenApplications() {
        appScope.launch(Dispatchers.IO) {
            val installedItems = packageManager
                .getInstalledPackagesCompat()
                ?.map { it.toInstalledItem() }
            if (installedItems != null) {
                Database.InstalledAdapter.putAll(installedItems)
            }
            registerReceiver(
                InstalledAppReceiver(packageManager),
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                    addDataScheme("package")
                },
            )
        }
    }

    private fun checkLanguage() {
        appScope.launch {
            val lastSetLanguage = settingsRepository.getInitial().language
            val systemSetLanguage = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (systemSetLanguage != lastSetLanguage && lastSetLanguage != "system") {
                settingsRepository.setLanguage(systemSetLanguage)
            }
        }
    }

    private fun updatePreference() {
        appScope.launch {
            launch {
                settingsRepository.get { unstableUpdate }.drop(1).collect {
                    SyncService.forceSyncAll(applicationContext)
                }
            }
            launch {
                settingsRepository.get { alternativeNotificationIcon }.collect {
                    NotificationIconHelper.setAlternative(applicationContext, it)
                }
            }
            launch {
                settingsRepository.get { isoDateFormat }.collect {
                    DateFormatter.useIso8601 = it
                }
            }
            launch {
                combine(
                    settingsRepository.get { autoSync },
                    settingsRepository.get { autoSyncInterval },
                ) { syncMode, syncInterval ->
                    syncMode to syncInterval
                }.collectIndexed { index, (syncMode, syncInterval) ->

                    SyncService.Job.schedule(applicationContext, index > 0, syncMode, syncInterval)
                    if (syncMode == AutoSync.NEVER) {
                        WorkManager.getInstance(applicationContext).cancelUniqueWork("SyncWorker")
                    } else {
                        SyncWorker.schedulePeriodicSync(applicationContext, syncInterval)
                    }
                }
            }
            launch {
                settingsRepository.get { cleanUpInterval }.collect {
                    if (it == INFINITE) {
                        CleanUpWorker.removeAllSchedules(applicationContext)
                    } else {
                        CleanUpWorker.scheduleCleanup(applicationContext, it)
                    }
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val memoryCache = MemoryCache.Builder()
            .maxSizePercent(context, 0.25)
            .build()

        val diskCache = DiskCache.Builder()
            .directory(Cache.getImagesDir(this))
            .maxSizePercent(0.05)
            .build()

        return ImageLoader.Builder(this)
            .memoryCache(memoryCache)
            .diskCache(diskCache)
            .error(getDrawableCompat(R.drawable.ic_cannot_load).asImage())
            .crossfade(350)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { httpClient }))
                add(FallbackIconInterceptor())
            }
            .build()
    }
}

private class FallbackIconInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val initialResult = chain.proceed()
        if (initialResult is SuccessResult) return initialResult

        val originalData = when (val data = request.data) {
            is String -> data
            is android.net.Uri -> data.toString()
            else -> data?.toString() ?: return initialResult
        }

        if (!originalData.startsWith("http://") && !originalData.startsWith("https://")) {
            return initialResult
        }

        val fallbacks = mutableListOf<String>()

        // 1. If icons-DPI directory, fall back to default /icons/ directory with same filename
        if (originalData.contains(Regex("""/icons-\d+/"""))) {
            fallbacks.add(originalData.replace(Regex("""/icons-\d+/"""), "/icons/"))
        }

        // 2. Replace filename with icon.png (handling scanner bug or missing specific icon)
        val withIconPng = originalData.replaceAfterLast('/', "icon.png")
        if (withIconPng != originalData) {
            fallbacks.add(withIconPng)
        }

        // 3. If icons-DPI was replaced with icon.png, also try /icons/icon.png
        if (originalData.contains(Regex("""/icons-\d+/"""))) {
            fallbacks.add(withIconPng.replace(Regex("""/icons-\d+/"""), "/icons/"))
        }

        // 4. If localized metadata icon like .../en-US/icon.png, fallback to .../icon.png
        val strippedLocale = withIconPng.replace(Regex("""/[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]+)?/icon\.png$"""), "/icon.png")
        if (strippedLocale != withIconPng) {
            fallbacks.add(strippedLocale)
        }

        for (fallbackUrl in fallbacks.distinct()) {
            if (fallbackUrl == originalData) continue
            val fallbackRequest = request.newBuilder().data(fallbackUrl).build()
            val result = chain.withRequest(fallbackRequest).proceed()
            if (result is SuccessResult) {
                return result
            }
        }

        return initialResult
    }
}
