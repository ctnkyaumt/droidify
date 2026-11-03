package com.looker.droidify.utility.text

import android.text.style.ClickableSpan
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HtmlFormatterTest {

    @Test
    fun formatsHtmlWithAnchorTagAsClickableLink() {
        var clickedUrl: String? = null
        val htmlText = "<p>Check out <a href=\"https://example.com/app\">Example</a></p>"

        val formatted = formatHtml(htmlText) { clickedUrl = it }
        val clickableSpans = formatted.getSpans(0, formatted.length, ClickableSpan::class.java)

        assertTrue("Should have at least one ClickableSpan", clickableSpans.isNotEmpty())
        clickableSpans.first().onClick(View(org.robolectric.RuntimeEnvironment.getApplication()))
        assertEquals("https://example.com/app", clickedUrl)
    }

    @Test
    fun formatsPlainTextWithBareUrlAsClickableLink() {
        var clickedUrl: String? = null
        val plainText = "Visit https://github.com/Droid-ify/client for source code"

        val formatted = formatHtml(plainText) { clickedUrl = it }
        val clickableSpans = formatted.getSpans(0, formatted.length, ClickableSpan::class.java)

        assertTrue("Should have at least one ClickableSpan for bare URL", clickableSpans.isNotEmpty())
        clickableSpans.first().onClick(View(org.robolectric.RuntimeEnvironment.getApplication()))
        assertEquals("https://github.com/Droid-ify/client", clickedUrl)
    }
}
