package com.smartview.glassai.glasses

/**
 * Pages contain at most [maxChars] UTF-16 units, without splitting surrogate pairs.
 * Optional line/column limits also bound explicit newlines and conservative word wrapping.
 * Breaks prefer the last sentence end (。！？.!? or newline), then comma (，,), then space,
 * then a hard cut. Nonblank input is preserved exactly across pages, including whitespace;
 * whitespace-only text yields listOf("").
 * Limits must be positive and fit the next complete character/line break; a supplementary
 * character or CRLF requires at least two UTF-16 units.
 */
fun paginate(
    text: String,
    maxChars: Int = DisplayCard.PAGE_CHARS,
    maxLines: Int = Int.MAX_VALUE,
    columns: Int = Int.MAX_VALUE,
): List<String> {
    require(maxChars > 0) { "maxChars must be positive" }
    require(maxLines > 0 && columns > 0) { "Line and column budgets must be positive" }
    val content = text
    if (content.isBlank()) return listOf("")
    val pages = mutableListOf<String>()
    var start = 0
    while (start < content.length) {
        // Subtract first to keep even Int.MAX_VALUE safe from overflow.
        var end = content.safeEnd(start + minOf(maxChars, content.length - start))
        while (end > start && conservativeDisplayLines(content.substring(start, end), columns) > maxLines) {
            end = content.safeEnd(end - 1)
        }
        require(end > start) { "Page budget must fit the next complete character or line break" }
        val cut = if (end == content.length) end else {
            val window = start until end
            val boundary = window.lastOrNull { content[it] in "。！？.!?\n" }
                ?: window.lastOrNull { content[it] in "，," }
                ?: window.lastOrNull { content[it] == ' ' }
            boundary?.plus(1) ?: end
        }
        pages.add(content.substring(start, cut))
        start = cut
    }
    return pages
}

/**
 * Conservative model, not a font measurement: each Unicode code point consumes one em cell,
 * tabs consume four, and words wrap before a line when they do not fit. CRLF is one break.
 * Combining/joiner code points also consume cells (overestimation rather than lost content).
 * Real fallback fonts, shaping and dense Chinese still require hardware verification.
 */
internal fun conservativeDisplayLines(text: String, columns: Int): Int {
    require(columns > 0)
    var lines = 1
    var occupied = 0
    var index = 0
    fun addCells(count: Int) {
        repeat(count) {
            if (occupied == columns) { lines++; occupied = 0 }
            occupied++
        }
    }
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        index += Character.charCount(codePoint)
        when {
            codePoint.isDisplayLineBreak() -> {
                if (codePoint == '\r'.code && index < text.length && text[index] == '\n') index++
                lines++
                occupied = 0
            }
            Character.isWhitespace(codePoint) -> addCells(if (codePoint == '\t'.code) 4 else 1)
            else -> {
                var wordLength = 1
                while (index < text.length) {
                    val next = text.codePointAt(index)
                    if (next.isDisplayLineBreak() || Character.isWhitespace(next)) break
                    wordLength++
                    index += Character.charCount(next)
                }
                if (occupied > 0 && wordLength > columns - occupied) { lines++; occupied = 0 }
                addCells(wordLength)
            }
        }
    }
    return lines
}

private fun Int.isDisplayLineBreak() = when (this) {
    10, 13, 0x85, 0x2028, 0x2029 -> true
    else -> false
}

/** Only nonpaged text is shortened. An ellipsis exposes omitted content; tails keep newest text. */
internal fun String.boundedDisplayText(maxChars: Int, maxLines: Int, columns: Int, keepTail: Boolean = false): String {
    require(maxChars > 0 && maxLines > 0 && columns > 0)
    if (length <= maxChars && conservativeDisplayLines(this, columns) <= maxLines) return this
    var content = if (keepTail) takeLastDisplayChars(maxChars - 1) else takeDisplayChars(maxChars - 1)
    fun marked() = if (keepTail) "…$content" else "$content…"
    while (content.isNotEmpty() && conservativeDisplayLines(marked(), columns) > maxLines) {
        content = if (keepTail) content.takeLastDisplayChars(content.length - 1) else content.takeDisplayChars(content.length - 1)
    }
    return marked()
}

/** Prefix/suffix limits use the same UTF-16 budget as pagination. */
internal fun String.takeDisplayChars(maxChars: Int): String = substring(0, safeEnd(minOf(maxChars, length)))

internal fun String.takeLastDisplayChars(maxChars: Int): String {
    var start = (length - maxChars).coerceAtLeast(0)
    if (start > 0 && start < length && this[start].isLowSurrogate() && this[start - 1].isHighSurrogate()) start++
    return substring(start)
}

private fun String.safeEnd(end: Int): Int =
    if (end > 0 && end < length &&
        ((this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) || (this[end - 1] == '\r' && this[end] == '\n'))
    ) end - 1 else end
