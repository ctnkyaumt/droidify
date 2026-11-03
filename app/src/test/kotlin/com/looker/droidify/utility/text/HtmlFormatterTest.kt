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

    @Test
    fun formatsHtmlWithBothAnchorTagAndBareUrl() {
        val clickedUrls = mutableListOf<String>()
        val htmlText = "<p>Visit <a href=\"https://example.com/site\">our site</a> or check https://github.com/repo</p>"

        val formatted = formatHtml(htmlText) { clickedUrls.add(it) }
        val clickableSpans = formatted.getSpans(0, formatted.length, ClickableSpan::class.java)

        assertEquals(2, clickableSpans.size)
        val view = View(org.robolectric.RuntimeEnvironment.getApplication())
        clickableSpans.forEach { it.onClick(view) }
        assertTrue(clickedUrls.contains("https://example.com/site"))
        assertTrue(clickedUrls.contains("https://github.com/repo"))
    }
}
