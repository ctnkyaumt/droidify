package com.looker.droidify.utility

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class DateFormatterTest {

    @After
    fun tearDown() {
        DateFormatter.useIso8601 = false
    }

    @Test
    fun `formatDate with useIso8601 true formats as yyyy-MM-dd`() {
        DateFormatter.useIso8601 = true
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.JANUARY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val formatted = DateFormatter.formatDate(calendar.timeInMillis)
        assertEquals("2023-01-15", formatted)
    }

    @Test
    fun `formatDate with explicit German locale produces day month year format`() {
        DateFormatter.useIso8601 = false
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.JANUARY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val formatted = DateFormatter.formatDate(calendar.timeInMillis, Locale.GERMANY)
        val expected = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.GERMANY).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(calendar.time)
        assertEquals(expected, formatted)
        assertEquals("15.01.23", formatted)
    }

    @Test
    fun `formatDate with explicit US locale produces month day year format`() {
        DateFormatter.useIso8601 = false
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.JANUARY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val formatted = DateFormatter.formatDate(calendar.timeInMillis, Locale.US)
        val expected = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(calendar.time)
        assertEquals(expected, formatted)
        assertEquals("1/15/23", formatted)
    }

    @Test
    fun `formatDate with useIso8601 false does not force yyyy-MM-dd format`() {
        DateFormatter.useIso8601 = false
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.JANUARY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val formatted = DateFormatter.formatDate(calendar.timeInMillis, Locale.GERMANY)
        assertEquals(
            java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT, Locale.GERMANY).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(calendar.time),
            formatted,
        )
    }
}
