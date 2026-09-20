package com.forcepushdev.preflight.services

import junit.framework.TestCase

class AnchorRelocatorTest : TestCase() {

    private val file = listOf("a", "b", "c", "d", "e")

    fun testRelocate_returnsSameRange_whenTextStillAtStoredPosition() {
        assertEquals(2..2, AnchorRelocator.relocate(file, startLine = 2, anchorText = "c"))
    }

    fun testRelocate_findsText_afterLinesInsertedAbove() {
        val shifted = listOf("x", "y") + file

        assertEquals(4..4, AnchorRelocator.relocate(shifted, startLine = 2, anchorText = "c"))
    }

    fun testRelocate_findsText_afterLinesDeletedAbove() {
        val shifted = file.drop(2)

        assertEquals(0..0, AnchorRelocator.relocate(shifted, startLine = 2, anchorText = "c"))
    }

    fun testRelocate_multiLineAnchor_returnsFullRange() {
        val shifted = listOf("x") + file

        assertEquals(2..3, AnchorRelocator.relocate(shifted, startLine = 1, anchorText = "b\nc"))
    }

    fun testRelocate_multiLineAnchor_doesNotMatchPartialBlock() {
        val lines = listOf("a", "b", "x", "c")

        assertNull(AnchorRelocator.relocate(lines, startLine = 1, anchorText = "b\nc"))
    }

    fun testRelocate_returnsNull_whenTextIsGone() {
        assertNull(AnchorRelocator.relocate(file, startLine = 2, anchorText = "gone"))
    }

    fun testRelocate_picksOccurrenceNearestToStoredLine() {
        val lines = listOf("}", "x", "}", "y", "z", "w", "}")

        assertEquals(6..6, AnchorRelocator.relocate(lines, startLine = 5, anchorText = "}"))
    }

    fun testRelocate_ignoresIndentationAndTrailingWhitespace() {
        val reindented = listOf("new", "        c  ")

        assertEquals(1..1, AnchorRelocator.relocate(reindented, startLine = 2, anchorText = "    c"))
    }

    fun testRelocate_normalizesWindowsLineEndingsInAnchorText() {
        assertEquals(1..2, AnchorRelocator.relocate(file, startLine = 1, anchorText = "b\r\nc"))
    }

    fun testRelocate_returnsNull_forBlankAnchorText() {
        assertNull(AnchorRelocator.relocate(listOf("a", "", "b"), startLine = 1, anchorText = ""))
        assertNull(AnchorRelocator.relocate(listOf("a", "", "b"), startLine = 1, anchorText = "  \n "))
    }

    fun testRelocate_startLineBeyondFile_stillSearchesWholeFile() {
        assertEquals(1..1, AnchorRelocator.relocate(file, startLine = 99, anchorText = "b"))
    }
}
