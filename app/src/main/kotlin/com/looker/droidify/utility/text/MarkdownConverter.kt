package com.looker.droidify.utility.text

private val HTML_TAG_REGEX = Regex(
    """<\s*(?:p|br|div|ul|ol|li|h[1-6]|b|strong|i|em|a|code|pre|tt|table|tr|td|th)\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)

fun isHtml(text: String): Boolean = HTML_TAG_REGEX.containsMatchIn(text)

fun markdownOrHtmlToHtml(text: String): String {
    if (text.isEmpty() || isHtml(text)) return text
    return markdownToHtml(text)
}

fun markdownToHtml(markdown: String): String {
    if (markdown.isEmpty()) return ""
    val normalized = markdown.replace("\r\n", "\n").replace("\r", "\n")

    // 1. Extract inline code blocks so their contents aren't affected by formatting
    val codeBlocks = mutableListOf<String>()
    val codePlaceholderPrefix = "\u0000CODE_"
    val codePlaceholderSuffix = "\u0000"
    val codeRegex = Regex("""`([^`\n]+)`""")
    val textWithoutCode = codeRegex.replace(normalized) { match ->
        val index = codeBlocks.size
        codeBlocks.add(escapeHtml(match.groupValues[1]))
        "$codePlaceholderPrefix$index$codePlaceholderSuffix"
    }

    // 2. Escape HTML special characters in the text
    val escaped = escapeHtml(textWithoutCode)

    // 3. Process line-by-line blocks (headings, lists, paragraphs)
    val lines = escaped.lines()
    val result = StringBuilder()

    var inBulletList = false
    val currentParagraph = mutableListOf<String>()

    fun flushParagraph() {
        if (currentParagraph.isNotEmpty()) {
            val content = currentParagraph.joinToString("<br/>")
            result.append("<p>").append(content).append("</p>")
            currentParagraph.clear()
        }
    }

    fun closeList() {
        if (inBulletList) {
            result.append("</ul>")
            inBulletList = false
        }
    }

    for (rawLine in lines) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) {
            flushParagraph()
            closeList()
            continue
        }

        // Heading: e.g. # Heading 1, ## Heading 2, etc.
        val headingMatch = Regex("""^(#{1,6})\s+(.+)$""").matchEntire(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            closeList()
            val level = headingMatch.groupValues[1].length
            val headingText = formatInline(headingMatch.groupValues[2].trim())
            result.append("<h").append(level).append(">")
                .append(headingText)
                .append("</h").append(level).append(">")
            continue
        }

        // Bullet list item: - item, * item, + item
        val bulletMatch = Regex("""^[-*+]\s+(.+)$""").matchEntire(trimmed)
        if (bulletMatch != null) {
            flushParagraph()
            if (!inBulletList) {
                result.append("<ul>")
                inBulletList = true
            }
            val itemText = formatInline(bulletMatch.groupValues[1].trim())
            result.append("<li>").append(itemText).append("</li>")
            continue
        }

        // Numbered list item: 1. item
        val numberedMatch = Regex("""^(\d+\.)\s+(.+)$""").matchEntire(trimmed)
        if (numberedMatch != null) {
            closeList()
            val num = numberedMatch.groupValues[1]
            val itemText = formatInline(numberedMatch.groupValues[2].trim())
            currentParagraph.add("$num $itemText")
            continue
        }

        // Regular line
        closeList()
        currentParagraph.add(formatInline(trimmed))
    }

    flushParagraph()
    closeList()

    // 4. Restore inline code
    var finalHtml = result.toString()
    for (i in codeBlocks.indices) {
        val placeholder = "$codePlaceholderPrefix$i$codePlaceholderSuffix"
        finalHtml = finalHtml.replace(placeholder, "<tt>${codeBlocks[i]}</tt>")
    }

    return finalHtml
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun formatInline(text: String): String {
    var str = text

    // Links: [text](url)
    str = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""").replace(str) { match ->
        val linkText = match.groupValues[1]
        val url = match.groupValues[2]
        """<a href="$url">$linkText</a>"""
    }

    // Bold italic: ***text*** or ___text___
    str = Regex("""\*\*\*(.+?)\*\*\*""").replace(str, "<b><i>$1</i></b>")
    str = Regex("""___(.+?)___""").replace(str, "<b><i>$1</i></b>")

    // Bold: **text** or __text__
    str = Regex("""\*\*(.+?)\*\*""").replace(str, "<b>$1</b>")
    str = Regex("""__(.+?)__""").replace(str, "<b>$1</b>")

    // Italic: *text* or _text_
    str = Regex("""\*(?!\s)([^\n*]+?)(?<!\s)\*""").replace(str, "<i>$1</i>")
    str = Regex("""(?<=\s|^|[^\w])_(?!\s)([^\n_]+?)(?<!\s)_(?=\s|$|[^\w])""").replace(str, "<i>$1</i>")

    return str
}
