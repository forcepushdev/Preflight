package com.forcepushdev.preflight.toolWindow

import junit.framework.TestCase

class TextSelectionTest : TestCase() {

    // Rows 10px per character, starting at x = 20.
    private fun row(group: Int, top: Int, text: String, separator: String) =
        TextRow(group, top, 20, text, IntArray(text.length + 1) { 20 + it * 10 }, 400, separator)

    private val rows = listOf(
        row(0, 0, "hello wide", " "),
        row(0, 20, "world", "\n\n"),
        row(1, 50, "reply", "\n")
    )

    fun testHit_onText_nearestBoundary() {
        assertEquals(TextPos(0, 2), TextSelection.hit(rows, 42, 5))
    }

    fun testHit_outsideRows_null() {
        assertNull(TextSelection.hit(rows, 42, 45))
    }

    fun testNearest_belowLastRow_endOfText() {
        assertEquals(TextPos(2, 5), TextSelection.nearest(rows, 0, 500))
    }

    fun testNearest_aboveFirstRow_start() {
        assertEquals(TextPos(0, 0), TextSelection.nearest(rows, 300, -5))
    }

    fun testText_acrossSoftWrapAndGroups_usesSeparators() {
        assertEquals("wide world\n\nre", TextSelection.text(rows, TextPos(0, 6), TextPos(2, 2)))
    }

    fun testText_backwardsSelection_sameText() {
        assertEquals("llo", TextSelection.text(rows, TextPos(0, 5), TextPos(0, 2)))
    }

    fun testWord_aroundPosition() {
        assertEquals(TextPos(0, 6) to TextPos(0, 10), TextSelection.word(rows, TextPos(0, 8)))
    }

    fun testGroup_coversAllRowsOfComment() {
        assertEquals(TextPos(0, 0) to TextPos(1, 5), TextSelection.group(rows, TextPos(1, 2)))
    }
}
