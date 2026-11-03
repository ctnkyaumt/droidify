package com.looker.droidify.utility

import android.content.Context
import android.content.SharedPreferences
import com.looker.droidify.R
import com.looker.droidify.index.RepositoryUpdater

object RepoSyncErrorManager {
    private const val PREFS_NAME = "repo_sync_errors"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getError(context: Context, repoId: Long): String? =
        getPrefs(context).getString(repoId.toString(), null)

    fun setError(context: Context, repoId: Long, error: String) {
        getPrefs(context).edit().putString(repoId.toString(), error).apply()
    }

    fun clearError(context: Context, repoId: Long) {
        getPrefs(context).edit().remove(repoId.toString()).apply()
    }

    fun formatError(context: Context, exception: Throwable?): String {
        if (exception == null) return context.getString(R.string.unknown_error_DESC)
        val typeDesc = when (exception) {
            is RepositoryUpdater.UpdateException -> when (exception.errorType) {
                RepositoryUpdater.ErrorType.NETWORK -> context.getString(R.string.network_error_DESC)
                RepositoryUpdater.ErrorType.HTTP -> context.getString(
                    R.string.http_error_DESC,
                    exception.message.orEmpty(),
                )
                RepositoryUpdater.ErrorType.VALIDATION -> context.getString(R.string.validation_index_error_DESC)
                RepositoryUpdater.ErrorType.PARSING -> context.getString(R.string.parsing_index_error_DESC)
            }
            else -> exception.localizedMessage?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.unknown_error_DESC)
        }
        val detail = exception.message
        return if (!detail.isNullOrBlank() && !typeDesc.contains(detail)) {
            "$typeDesc: $detail"
        } else {
            typeDesc
        }
    }
}
