package com.forcepushdev.preflight.toolWindow

/**
 * Splits a comment body into prose lines and fenced code blocks, marking inline `code` spans.
 * Only code formatting is recognised; any other Markdown stays literal text.
 */
internal object CommentText {

    sealed interface Block

    /** One source line of prose. */
    data class Prose(val segments: List<Segment>) : Block

    data class Code(val lines: List<String>) : Block

    /** [span] identifies the inline code span the text belongs to, or is [PLAIN]. */
    data class Segment(val text: String, val span: Int = PLAIN) {
        val isCode: Boolean get() = span != PLAIN
    }

    const val PLAIN = -1

    private const val FENCE = "```"
    private val FENCE_INFO = Regex("[\\w+#.-]*")
    private val INLINE_CODE = Regex("(`+)(.+?)\\1")

    fun parse(source: String): List<Block> {
        val text = source.replace("\r\n", "\n")
        val blocks = mutableListOf<Block>()
        var nextSpan = 0

        fun prose(chunk: String) {
            if (chunk.isEmpty()) return
            for (line in chunk.split('\n')) {
                val segments = mutableListOf<Segment>()
                var pos = 0
                for (match in INLINE_CODE.findAll(line)) {
                    if (match.range.first > pos) segments += Segment(line.substring(pos, match.range.first))
                    segments += Segment(match.groupValues[2], nextSpan++)
                    pos = match.range.last + 1
                }
                if (pos < line.length) segments += Segment(line.substring(pos))
                blocks += Prose(segments)
            }
        }

        var pos = 0
        while (true) {
            val open = text.indexOf(FENCE, pos)
            if (open < 0) {
                prose(text.substring(pos))
                break
            }
            prose(text.substring(pos, open).removeSuffix("\n"))
            var bodyStart = open + FENCE.length
            val infoEnd = text.indexOf('\n', bodyStart)
            // A language tag (or nothing) up to the line break belongs to the fence, not the code.
            if (infoEnd >= 0 && FENCE_INFO.matches(text.substring(bodyStart, infoEnd))) bodyStart = infoEnd + 1
            val close = text.indexOf(FENCE, bodyStart)
            val body = text.substring(bodyStart, if (close < 0) text.length else close).removeSuffix("\n")
            if (body.isNotEmpty()) blocks += Code(body.split('\n'))
            if (close < 0) break
            pos = close + FENCE.length
            if (text.startsWith("\n", pos)) pos++
        }
        return blocks.ifEmpty { listOf(Prose(emptyList())) }
    }

    private class Word(val segments: List<Segment>, val gapSpan: Int)

    /**
     * Wraps a prose line at spaces into rows no wider than [maxWidth], as measured by [measure] for each
     * run of equally formatted text. A space inside a code span stays code-formatted. A word wider than
     * [maxWidth] gets a row of its own.
     */
    fun wrap(segments: List<Segment>, maxWidth: Int, measure: (Segment) -> Int): List<List<Segment>> {
        val words = mutableListOf<Word>()
        var current = mutableListOf<Segment>()
        var gapSpan = PLAIN
        for (segment in segments) {
            segment.text.split(' ').forEachIndexed { i, part ->
                if (i > 0) {
                    if (current.isNotEmpty()) words += Word(current, gapSpan)
                    current = mutableListOf()
                    gapSpan = segment.span
                }
                if (part.isNotEmpty()) current += Segment(part, segment.span)
            }
        }
        if (current.isNotEmpty()) words += Word(current, gapSpan)
        if (words.isEmpty()) return listOf(emptyList())

        fun width(row: List<Segment>) = merge(row).sumOf(measure)

        val rows = mutableListOf<List<Segment>>()
        var row = words.first().segments
        for (word in words.drop(1)) {
            val candidate = row + Segment(" ", word.gapSpan) + word.segments
            if (maxWidth <= 0 || width(candidate) <= maxWidth) {
                row = candidate
            } else {
                rows += merge(row)
                row = word.segments
            }
        }
        rows += merge(row)
        return rows
    }

    /** Breaks a code line into rows no wider than [maxWidth], at any character. */
    fun wrapCode(line: String, maxWidth: Int, measure: (String) -> Int): List<String> {
        if (maxWidth <= 0 || measure(line) <= maxWidth) return listOf(line)
        val rows = mutableListOf<String>()
        var start = 0
        while (start < line.length) {
            var end = start + 1
            while (end < line.length && measure(line.substring(start, end + 1)) <= maxWidth) end++
            rows += line.substring(start, end)
            start = end
        }
        return rows
    }

    private fun merge(segments: List<Segment>): List<Segment> {
        val merged = mutableListOf<Segment>()
        for (segment in segments) {
            val last = merged.lastOrNull()
            if (last != null && last.span == segment.span) {
                merged[merged.lastIndex] = Segment(last.text + segment.text, last.span)
            } else {
                merged += segment
            }
        }
        return merged
    }
}
