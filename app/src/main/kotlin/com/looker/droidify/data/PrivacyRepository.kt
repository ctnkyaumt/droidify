package com.looker.droidify.data

import com.looker.droidify.data.local.model.RBLog
import com.looker.droidify.database.Database
import com.looker.droidify.datastore.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.*

class PrivacyRepository(
    private val settingsRepo: SettingsRepository,
) {
    private val cc = Dispatchers.IO

    fun getRBLogs(packageName: String): Flow<List<RBLog>> =
        Database.RBLogAdapter.getStream(packageName)

    suspend fun upsertRBLogs(lastModified: Date, logs: List<RBLog>) {
        settingsRepo.setRbLogLastModified(lastModified)
        withContext(cc) { Database.RBLogAdapter.upsert(logs) }
    }

    suspend fun clearRbLogs() {
        withContext(cc) { Database.RBLogAdapter.deleteAll() }
        settingsRepo.clearRbLogLastModified()
    }
}
