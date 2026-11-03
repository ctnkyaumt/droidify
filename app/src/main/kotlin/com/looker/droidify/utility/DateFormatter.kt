package com.looker.droidify.utility

import android.content.Context
import android.content.res.Resources
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.os.Build
import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatDelegate
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class RelativeAgeUnit { DAY, WEEK, MONTH, YEAR }

/** [amount] 0 with [RelativeAgeUnit.DAY] means "today", 1 means "yesterday". */
data class RelativeAge(val amount: Int, val unit: RelativeAgeUnit)

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

    /**
     * Picks the amount and unit of a coarse "how long ago" text: under a day is today, then days
     * (< 7), weeks (< 5), months of 30 days (< 12) and finally years of 365 days (at least 1).
     * Dates in the future count as today.
     */
    fun relativeAge(millis: Long, nowMillis: Long): RelativeAge {
        val days = ((nowMillis - millis).coerceAtLeast(0L) / DateUtils.DAY_IN_MILLIS).toInt()
        val weeks = days / 7
        val months = days / 30
        return when {
            days < 7 -> RelativeAge(days, RelativeAgeUnit.DAY)
            weeks < 5 -> RelativeAge(weeks, RelativeAgeUnit.WEEK)
            months < 12 -> RelativeAge(months, RelativeAgeUnit.MONTH)
            else -> RelativeAge((days / 365).coerceAtLeast(1), RelativeAgeUnit.YEAR)
        }
    }

    /** Localized text such as "3 weeks ago". Not affected by [useIso8601], it is not a date. */
    fun formatRelative(
        millis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        locale: Locale? = null,
    ): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return DateUtils.getRelativeTimeSpanString(
                millis,
                nowMillis,
                DateUtils.DAY_IN_MILLIS,
            ).toString()
        }
        val formatter = RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(locale ?: getEffectiveLocale()),
        )
        val age = relativeAge(millis, nowMillis)
        val direction = RelativeDateTimeFormatter.Direction.LAST
        return when {
            age.unit == RelativeAgeUnit.DAY && age.amount == 0 -> formatter.format(
                RelativeDateTimeFormatter.Direction.THIS,
                RelativeDateTimeFormatter.AbsoluteUnit.DAY,
            )
            age.unit == RelativeAgeUnit.DAY && age.amount == 1 -> formatter.format(
                direction,
                RelativeDateTimeFormatter.AbsoluteUnit.DAY,
            )
            else -> formatter.format(
                age.amount.toDouble(),
                direction,
                when (age.unit) {
                    RelativeAgeUnit.DAY -> RelativeDateTimeFormatter.RelativeUnit.DAYS
                    RelativeAgeUnit.WEEK -> RelativeDateTimeFormatter.RelativeUnit.WEEKS
                    RelativeAgeUnit.MONTH -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
                    RelativeAgeUnit.YEAR -> RelativeDateTimeFormatter.RelativeUnit.YEARS
                },
            )
        }
    }
}
