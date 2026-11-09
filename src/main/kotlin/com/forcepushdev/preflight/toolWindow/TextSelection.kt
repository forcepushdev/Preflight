package com.forcepushdev.preflight.toolWindow

import kotlin.math.abs

/**
 * A painted row of selectable comment text, in inlay coordinates. [xs] holds the x of every character
 * boundary, so it is one longer than [text]. [separator] joins the row to the next one when copying.
 */
internal data class TextRow(
    val group: Int,
    val top: Int,
    val height: Int,
    val text: String,
    val xs: IntArray,
    val right: Int,
    val separator: String
)

internal data class TextPos(val row: Int, val col: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos) = compareValuesBy(this, other, { it.row }, { it.col })
}

/** Hit-testing and text extraction for a selection across [TextRow]s. */
internal object TextSelection {

    private const val SLOP = 4

    /** The position a press at ([x], [y]) starts a selection from, or null when it is not on text. */
    fun hit(rows: List<TextRow>, x: Int, y: Int): TextPos? {
        val index = rows.indexOfFirst { y >= it.top && y < it.top + it.height && x >= it.xs.first() - SLOP && x <= it.right }
        return if (index < 0) null else TextPos(index, nearestCol(rows[index], x))
    }

    /** The position a drag to ([x], [y]) extends the selection to, clamped to the text. */
    fun nearest(rows: List<TextRow>, x: Int, y: Int): TextPos? {
        if (rows.isEmpty()) return null
        if (y < rows.first().top) return TextPos(0, 0)
        val index = rows.indexOfLast { it.top <= y }
        val row = rows[index]
        if (index == rows.lastIndex && y >= row.top + row.height) return TextPos(index, row.text.length)
        return TextPos(index, nearestCol(row, x))
    }

    fun text(rows: List<TextRow>, a: TextPos, b: TextPos): String {
        val (start, end) = if (a <= b) a to b else b to a
        val out = StringBuilder()
        for (i in start.row..end.row) {
            val row = rows.getOrNull(i) ?: break
            val from = if (i == start.row) start.col else 0
            val to = if (i == end.row) end.col else row.text.length
            out.append(row.text, from.coerceIn(0, row.text.length), to.coerceIn(from, row.text.length))
            if (i != end.row) out.append(row.separator)
        }
        return out.toString()
    }

    /** The word around [pos], or just [pos] when it is not on a word character. */
    fun word(rows: List<TextRow>, pos: TextPos): Pair<TextPos, TextPos> {
        val text = rows[pos.row].text
        fun isWord(i: Int) = i in text.indices && (text[i].isLetterOrDigit() || text[i] == '_')
        var from = pos.col
        var to = pos.col
        while (isWord(from - 1)) from--
        while (isWord(to)) to++
        return TextPos(pos.row, from) to TextPos(pos.row, to)
    }

    /** All rows of the comment or reply [pos] is in. */
    fun group(rows: List<TextRow>, pos: TextPos): Pair<TextPos, TextPos> {
        val group = rows[pos.row].group
        val first = rows.indexOfFirst { it.group == group }
        val last = rows.indexOfLast { it.group == group }
        return TextPos(first, 0) to TextPos(last, rows[last].text.length)
    }

    /** The column of the character boundary closest to [x]. */
    fun nearestCol(row: TextRow, x: Int): Int = row.xs.indices.minBy { abs(row.xs[it] - x) }
}
