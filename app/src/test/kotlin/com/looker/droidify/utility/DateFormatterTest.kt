package com.looker.droidify.utility

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar
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
    fun `formatDate with useIso8601 false does not force yyyy-MM-dd format`() {
        DateFormatter.useIso8601 = false
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.JANUARY, 15, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val formatted = DateFormatter.formatDate(calendar.timeInMillis)
        // Default locale short date format is typically MM/dd/yy or dd.MM.yy or similar,
        // but not "yyyy-MM-dd"
        assertEquals(
            java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(calendar.time),
            formatted
        )
    }
}
