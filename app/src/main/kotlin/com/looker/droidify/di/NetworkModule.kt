package com.looker.droidify.di

import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.model.ProxyPreference
import com.looker.droidify.datastore.model.ProxyType
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.OkHttpDownloader
import com.looker.droidify.utility.common.log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Singleton
    @Provides
    fun provideHttpClient(settingsRepository: SettingsRepository): OkHttpClient {
        return OkHttpClient.Builder()
            .proxySelector(AppProxySelector(settingsRepository))
            .connectTimeout(30_000L, TimeUnit.MILLISECONDS)
            .readTimeout(15_000L, TimeUnit.MILLISECONDS)
            .writeTimeout(15_000L, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Droid-ify")
                        .build(),
                )
            }
            .build()
    }

    @Singleton
    @Provides
    fun provideDownloader(
        httpClient: OkHttpClient,
        @IoDispatcher
        dispatcher: CoroutineDispatcher,
    ): Downloader = OkHttpDownloader(
        client = httpClient,
        dispatcher = dispatcher,
    )
}

class AppProxySelector(
    settingsRepository: SettingsRepository,
    private val defaultSelector: ProxySelector? = ProxySelector.getDefault(),
) : ProxySelector() {

    @Volatile
    private var proxyPreference: ProxyPreference = runBlocking {
        runCatching { settingsRepository.getInitial().proxy }.getOrDefault(ProxyPreference())
    }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            settingsRepository.data.collect { settings ->
                proxyPreference = settings.proxy
            }
        }
    }

    override fun select(uri: URI?): List<Proxy> {
        val customProxy = proxyPreference.toCustomProxy()
        return if (customProxy != null) {
            listOf(customProxy)
        } else {
            defaultSelector?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        defaultSelector?.connectFailed(uri, sa, ioe)
    }
}

private fun ProxyPreference.toCustomProxy(): Proxy? {
    if (type == ProxyType.DIRECT) return null
    val socketAddress = try {
        InetSocketAddress.createUnresolved(host, port)
    } catch (e: IllegalArgumentException) {
        log(e)
        null
    } ?: return null

    val androidProxyType = when (type) {
        ProxyType.DIRECT -> Proxy.Type.DIRECT
        ProxyType.HTTP -> Proxy.Type.HTTP
        ProxyType.SOCKS -> Proxy.Type.SOCKS
    }
    return Proxy(androidProxyType, socketAddress)
}
