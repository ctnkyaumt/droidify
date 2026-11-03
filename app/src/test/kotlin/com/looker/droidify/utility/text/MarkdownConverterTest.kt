package com.looker.droidify.utility.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownConverterTest {

    @Test
    fun `isHtml returns true for HTML content`() {
        assertTrue(isHtml("<p>Hello</p>"))
        assertTrue(isHtml("Line 1<br/>Line 2"))
        assertTrue(isHtml("<ul><li>Item</li></ul>"))
        assertTrue(isHtml("<b>Bold</b>"))
        assertTrue(isHtml("<a href=\"https://example.com\">Link</a>"))
        assertTrue(isHtml("<h1>Heading</h1>"))
    }

    @Test
    fun `isHtml returns false for markdown and plain text`() {
        assertFalse(isHtml(""))
        assertFalse(isHtml("Just plain text"))
        assertFalse(isHtml("# Heading"))
        assertFalse(isHtml("* Bullet item"))
        assertFalse(isHtml("1. Numbered item"))
        assertFalse(isHtml("**Bold text**"))
        assertFalse(isHtml("[Link](https://example.com)"))
        assertFalse(isHtml("Fix generic List<String> crash"))
    }

    @Test
    fun `markdownOrHtmlToHtml passes through existing HTML unchanged`() {
        val html = "<p>Bug fixes and improvements:</p><ul><li>Fixed crash</li></ul>"
        assertEquals(html, markdownOrHtmlToHtml(html))
    }

    @Test
    fun `markdownToHtml handles headings`() {
        assertEquals("<h1>Title</h1>", markdownToHtml("# Title"))
        assertEquals("<h2>Subtitle</h2>", markdownToHtml("## Subtitle"))
        assertEquals("<h3>Section</h3>", markdownToHtml("### Section"))
        assertEquals("<h4>Subsection</h4>", markdownToHtml("#### Subsection"))
        assertEquals("<h5>Minor</h5>", markdownToHtml("##### Minor"))
        assertEquals("<h6>Lowest</h6>", markdownToHtml("###### Lowest"))
    }

    @Test
    fun `markdownToHtml handles bullet lists`() {
        val md = """
            * First item
            * Second item
            * Third item
        """.trimIndent()
        val expected = "<ul><li>First item</li><li>Second item</li><li>Third item</li></ul>"
        assertEquals(expected, markdownToHtml(md))
    }

    @Test
    fun `markdownToHtml handles dash and plus bullet lists`() {
        val md = """
            - Item A
            + Item B
        """.trimIndent()
        val expected = "<ul><li>Item A</li><li>Item B</li></ul>"
        assertEquals(expected, markdownToHtml(md))
    }

    @Test
    fun `markdownToHtml handles numbered lists`() {
        val md = """
            1. Step one
            2. Step two
            3. Step three
        """.trimIndent()
        val expected = "<p>1. Step one<br/>2. Step two<br/>3. Step three</p>"
        assertEquals(expected, markdownToHtml(md))
    }

    @Test
    fun `markdownToHtml handles bold and italic`() {
        assertEquals("<p><b>Bold with stars</b></p>", markdownToHtml("**Bold with stars**"))
        assertEquals("<p><b>Bold with underscores</b></p>", markdownToHtml("__Bold with underscores__"))
        assertEquals("<p><i>Italic with stars</i></p>", markdownToHtml("*Italic with stars*"))
        assertEquals("<p><i>Italic with underscores</i></p>", markdownToHtml("_Italic with underscores_"))
        assertEquals("<p><b><i>Bold italic</i></b></p>", markdownToHtml("***Bold italic***"))
        assertEquals("<p><b><i>Bold italic underscores</i></b></p>", markdownToHtml("___Bold italic underscores___"))
    }

    @Test
    fun `markdownToHtml preserves snake_case without italicizing`() {
        val md = "Variable my_variable_name should not be italic"
        assertEquals("<p>Variable my_variable_name should not be italic</p>", markdownToHtml(md))
    }

    @Test
    fun `markdownToHtml handles inline code and preserves special chars inside it`() {
        val md = "Use `val x = a * b` and `List<String>` for types."
        val expected = "<p>Use <tt>val x = a * b</tt> and <tt>List&lt;String&gt;</tt> for types.</p>"
        assertEquals(expected, markdownToHtml(md))
    }

    @Test
    fun `markdownToHtml handles links`() {
        val md = "Visit [Droid-ify](https://github.com/Droid-ify/client) for more info."
        val expected = """<p>Visit <a href="https://github.com/Droid-ify/client">Droid-ify</a> for more info.</p>"""
        assertEquals(expected, markdownToHtml(md))
    }

    @Test
    fun `markdownToHtml escapes raw HTML angle brackets and ampersands`() {
        val md = "Added support for Tom & Jerry and Map<String, Int>"
        val expected = "<p>Added support for Tom &amp; Jerry and Map&lt;String, Int&gt;</p>"
        assertEquals(expected, markdownToHtml(md))
    }

    @Test
    fun `markdownToHtml handles mixed release notes`() {
        val md = """
            ## What's Changed
            * Fixed crash in `NetworkManager`
            * Added support for [F-Droid](https://f-droid.org)
            * Performance improvements

            Thanks to all contributors!
        """.trimIndent()

        val expected = "<h2>What's Changed</h2>" +
            "<ul>" +
            "<li>Fixed crash in <tt>NetworkManager</tt></li>" +
            "<li>Added support for <a href=\"https://f-droid.org\">F-Droid</a></li>" +
            "<li>Performance improvements</li>" +
            "</ul>" +
            "<p>Thanks to all contributors!</p>"

        assertEquals(expected, markdownToHtml(md))
    }

    @Test
    fun `markdownToHtml handles empty or blank string`() {
        assertEquals("", markdownToHtml(""))
        assertEquals("", markdownToHtml("   \n\n  "))
    }
}
