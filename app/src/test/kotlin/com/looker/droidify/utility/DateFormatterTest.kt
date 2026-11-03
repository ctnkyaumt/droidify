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

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000_000_000_000L

    private fun age(daysAgo: Long, extraMillis: Long = 0L) =
        DateFormatter.relativeAge(now - daysAgo * day - extraMillis, now)

    @Test
    fun `relativeAge under one day is today`() {
        assertEquals(RelativeAge(0, RelativeAgeUnit.DAY), age(0, 23L * 60 * 60 * 1000))
    }

    @Test
    fun `relativeAge in the future is today`() {
        assertEquals(RelativeAge(0, RelativeAgeUnit.DAY), DateFormatter.relativeAge(now + day, now))
    }

    @Test
    fun `relativeAge counts days below one week`() {
        assertEquals(RelativeAge(1, RelativeAgeUnit.DAY), age(1))
        assertEquals(RelativeAge(5, RelativeAgeUnit.DAY), age(5))
        assertEquals(RelativeAge(6, RelativeAgeUnit.DAY), age(6))
    }

    @Test
    fun `relativeAge counts weeks from seven days up to five weeks`() {
        assertEquals(RelativeAge(1, RelativeAgeUnit.WEEK), age(7))
        assertEquals(RelativeAge(3, RelativeAgeUnit.WEEK), age(21))
        assertEquals(RelativeAge(4, RelativeAgeUnit.WEEK), age(34))
    }

    @Test
    fun `relativeAge counts 30 day months from 35 days up to a year`() {
        assertEquals(RelativeAge(1, RelativeAgeUnit.MONTH), age(35))
        assertEquals(RelativeAge(2, RelativeAgeUnit.MONTH), age(60))
        assertEquals(RelativeAge(11, RelativeAgeUnit.MONTH), age(359))
    }

    @Test
    fun `relativeAge counts 365 day years with a minimum of one`() {
        assertEquals(RelativeAge(1, RelativeAgeUnit.YEAR), age(360))
        assertEquals(RelativeAge(1, RelativeAgeUnit.YEAR), age(365))
        assertEquals(RelativeAge(2, RelativeAgeUnit.YEAR), age(800))
    }
}
