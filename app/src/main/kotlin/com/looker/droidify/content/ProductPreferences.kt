package com.looker.droidify.content

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.looker.droidify.database.Database
import com.looker.droidify.model.ProductPreference
import com.looker.droidify.utility.common.extension.Json
import com.looker.droidify.utility.common.extension.parseDictionary
import com.looker.droidify.utility.common.extension.writeDictionary
import com.looker.droidify.utility.serialization.productPreference
import com.looker.droidify.utility.serialization.serialize
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object ProductPreferences {
    private val defaultProductPreference = ProductPreference(false, 0L)
    private lateinit var preferences: SharedPreferences
    private val mutableSubject = MutableSharedFlow<Pair<String, Long?>>(
        extraBufferCapacity = Int.MAX_VALUE,
    )
    private val subject = mutableSubject.asSharedFlow()
    private val mutablePreferredRepositorySubject = MutableSharedFlow<Pair<String, Long>>(
        extraBufferCapacity = Int.MAX_VALUE,
    )

    fun init(context: Context, scope: CoroutineScope) {
        preferences = context.getSharedPreferences("product_preferences", Context.MODE_PRIVATE)
        val allPreferences = preferences.all.keys.map { packageName ->
            Pair(packageName, this[packageName])
        }
        Database.LockAdapter.putAll(
            allPreferences.mapNotNull { (packageName, preference) ->
                preference.databaseVersionCode?.let { Pair(packageName, it) }
            },
        )
        Database.PreferredRepositoryAdapter.putAll(
            allPreferences.mapNotNull { (packageName, preference) ->
                preference.preferredRepositoryId.takeIf { it > 0L }?.let { Pair(packageName, it) }
            },
        )
        scope.launch {
            subject.collect { (packageName, versionCode) ->
                if (versionCode != null) {
                    Database.LockAdapter.put(Pair(packageName, versionCode))
                } else {
                    Database.LockAdapter.delete(packageName)
                }
            }
        }
        scope.launch {
            mutablePreferredRepositorySubject.collect { (packageName, repositoryId) ->
                if (repositoryId > 0L) {
                    Database.PreferredRepositoryAdapter.put(packageName, repositoryId)
                } else {
                    Database.PreferredRepositoryAdapter.delete(packageName)
                }
            }
        }
    }

    private val ProductPreference.databaseVersionCode: Long?
        get() = when {
            ignoreUpdates -> 0L
            ignoreVersionCode > 0L -> ignoreVersionCode
            else -> null
        }

    operator fun get(packageName: String): ProductPreference {
        return if (preferences.contains(packageName)) {
            try {
                Json.factory.createParser(preferences.getString(packageName, "{}"))
                    .use { it.parseDictionary { productPreference() } }
            } catch (e: Exception) {
                e.printStackTrace()
                defaultProductPreference
            }
        } else {
            defaultProductPreference
        }
    }

    operator fun set(packageName: String, productPreference: ProductPreference) {
        val oldProductPreference = this[packageName]
        preferences.edit {
            putString(
                packageName,
                ByteArrayOutputStream().apply {
                    Json.factory.createGenerator(this)
                        .use { it.writeDictionary(productPreference::serialize) }
                }.toByteArray().toString(Charset.defaultCharset()),
            )
        }
        if (oldProductPreference.ignoreUpdates != productPreference.ignoreUpdates ||
            oldProductPreference.ignoreVersionCode != productPreference.ignoreVersionCode
        ) {
            mutableSubject.tryEmit(Pair(packageName, productPreference.databaseVersionCode))
        }
        if (oldProductPreference.preferredRepositoryId != productPreference.preferredRepositoryId) {
            mutablePreferredRepositorySubject.tryEmit(
                Pair(packageName, productPreference.preferredRepositoryId),
            )
        }
    }
}
