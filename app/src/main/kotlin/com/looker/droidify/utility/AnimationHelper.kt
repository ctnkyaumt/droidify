package com.looker.droidify.utility

import android.content.Context
import android.provider.Settings

object AnimationHelper {
    @Volatile
    var reduceAnimations: Boolean = false

    fun shouldReduceAnimations(context: Context): Boolean {
        if (reduceAnimations) return true
        return try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    }
}
