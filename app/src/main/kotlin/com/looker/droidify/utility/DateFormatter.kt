package com.looker.droidify.utility

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatDelegate
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateFormatter {
    private val UTC = TimeZone.getTimeZone("UTC")

    @Volatile
    var useIso8601: Boolean = false

    private val ISO_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = UTC
            }
        }
    }

    fun getEffectiveLocale(context: Context? = null): Locale {
        try {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            if (!appLocales.isEmpty) {
                val appLocale = appLocales[0]
                if (appLocale != null) return appLocale
            }
        } catch (_: Exception) {
        }
        return context?.resources?.configuration?.locales?.get(0)
            ?: try {
                Resources.getSystem().configuration.locales.get(0)
            } catch (_: Exception) {
                null
            } ?: Locale.getDefault()
    }

    fun formatDate(millis: Long, locale: Locale? = null): String {
        return if (useIso8601) {
            ISO_DATE_FORMAT.get()!!.format(Date(millis))
        } else {
            val targetLocale = locale ?: getEffectiveLocale()
            DateFormat
                .getDateInstance(DateFormat.SHORT, targetLocale)
                .apply { timeZone = UTC }
                .format(Date(millis))
        }
    }

    fun formatRepoDate(context: Context, millis: Long, locale: Locale? = null): String {
        return if (useIso8601) {
            ISO_DATE_FORMAT.get()!!.format(Date(millis))
        } else {
            val date = Date(millis)
            val format = if (DateUtils.isToday(date.time)) {
                DateUtils.FORMAT_SHOW_TIME
            } else {
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
            }
            DateUtils.formatDateTime(context, date.time, format)
        }
    }
}
