package com.looker.droidify.utility

import android.content.Context
import android.text.format.DateUtils
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

    fun formatDate(millis: Long): String {
        return if (useIso8601) {
            ISO_DATE_FORMAT.get()!!.format(Date(millis))
        } else {
            DateFormat
                .getDateInstance(DateFormat.SHORT)
                .apply { timeZone = UTC }
                .format(Date(millis))
        }
    }

    fun formatRepoDate(context: Context, millis: Long): String {
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
