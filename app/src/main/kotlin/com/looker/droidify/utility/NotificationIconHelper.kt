package com.looker.droidify.utility

import android.content.Context
import com.looker.droidify.R

object NotificationIconHelper {
    private const val PREFS_NAME = "notification_icon_prefs"
    private const val KEY_ALTERNATIVE = "use_alternative_icon"

    @Volatile
    private var cachedAlternative: Boolean? = null

    fun getNotificationIcon(context: Context, defaultIconRes: Int): Int {
        val useAlternative = cachedAlternative ?: run {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(KEY_ALTERNATIVE, false).also { cachedAlternative = it }
        }
        return if (useAlternative) R.drawable.ic_notification_alternative else defaultIconRes
    }

    fun setAlternative(context: Context, enabled: Boolean) {
        cachedAlternative = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALTERNATIVE, enabled)
            .apply()
    }
}
