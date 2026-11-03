package com.looker.droidify.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.ConcurrentHashMap

object FailedUpdateTracker {

    private data class FailureRecord(
        val versionCode: Long,
        val failedTimestamp: Long,
        val retryCount: Int,
    )

    private var preferences: SharedPreferences? = null
    private val failedUpdates = ConcurrentHashMap<String, FailureRecord>()

    // Back off for 24 hours per retry
    private const val BASE_BACKOFF_MS = 24 * 60 * 60 * 1000L

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("failed_updates", Context.MODE_PRIVATE)
        preferences = prefs
        prefs.all.forEach { (pkg, value) ->
            if (value is String) {
                parseRecord(value)?.let { failedUpdates[pkg] = it }
            }
        }
    }

    private fun parseRecord(raw: String): FailureRecord? {
        val parts = raw.split(":")
        if (parts.size != 3) return null
        val versionCode = parts[0].toLongOrNull() ?: return null
        val timestamp = parts[1].toLongOrNull() ?: return null
        val count = parts[2].toIntOrNull() ?: return null
        return FailureRecord(versionCode, timestamp, count)
    }

    fun recordFailure(packageName: String, versionCode: Long) {
        val now = System.currentTimeMillis()
        val current = failedUpdates[packageName]
        val newCount = if (current != null && current.versionCode == versionCode) {
            current.retryCount + 1
        } else {
            1
        }
        val record = FailureRecord(
            versionCode = versionCode,
            failedTimestamp = now,
            retryCount = newCount,
        )
        failedUpdates[packageName] = record
        preferences?.edit {
            putString(packageName, "${record.versionCode}:${record.failedTimestamp}:${record.retryCount}")
        }
    }

    fun recordSuccess(packageName: String) {
        failedUpdates.remove(packageName)
        preferences?.edit {
            remove(packageName)
        }
    }

    fun shouldSkipAutoUpdate(packageName: String, targetVersionCode: Long): Boolean {
        val record = failedUpdates[packageName] ?: return false
        if (record.versionCode != targetVersionCode) {
            // New version available; reset backoff and allow update
            recordSuccess(packageName)
            return false
        }
        val now = System.currentTimeMillis()
        val elapsed = now - record.failedTimestamp
        val backoff = BASE_BACKOFF_MS * record.retryCount.coerceAtMost(7)
        return elapsed < backoff
    }

    fun clear() {
        failedUpdates.clear()
        preferences?.edit {
            clear()
        }
    }
}
