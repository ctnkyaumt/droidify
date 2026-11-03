package com.looker.droidify.utility.common

import android.util.Log
import com.looker.droidify.utility.DateFormatter
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

fun <T : CharSequence> T.nullIfEmpty(): T? {
    return if (isNullOrBlank()) null else this
}

fun Any.log(
    message: Any?,
    tag: String = this::class.java.simpleName + ".DEBUG",
    type: Int = Log.DEBUG,
) {
    Log.println(type, tag, message.toString())
}

fun formatDate(millis: Long): String = DateFormatter.formatDate(millis)

