package com.looker.droidify.utility

import android.content.Context
import com.looker.droidify.R

object NotificationIconHelper {
    @Volatile
    var useAlternative: Boolean = false

    fun getNotificationIcon(context: Context? = null, defaultIconRes: Int): Int {
        return if (useAlternative) R.drawable.ic_notification_alternative else defaultIconRes
    }

    fun setAlternative(context: Context? = null, enabled: Boolean) {
        useAlternative = enabled
    }
}
