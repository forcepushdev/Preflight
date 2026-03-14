package com.forcepushdev.preflight.toolWindow

import junit.framework.TestCase

class CommentInlayRendererTest : TestCase() {

    private val measure: (String) -> Int = { it.length * 8 }

    fun testWrapLines_shortText_returnsAsIs() {
        val result = CommentInlayRenderer.wrapLines("hello", 200, measure)

        assertEquals(listOf("hello"), result)
    }

    fun testWrapLines_longText_wrapsAtWordBoundary() {
        val result = CommentInlayRenderer.wrapLines("one two three four five", 80, measure)

        assertTrue(result.size > 1)
        result.forEach { line -> assertTrue(measure(line) <= 80) }
    }

    fun testWrapLines_singleLongWord_returnsAsIs() {
        val result = CommentInlayRenderer.wrapLines("verylongwordthatcannotbesplit", 50, measure)

        assertEquals(1, result.size)
    }

    fun testWrapLines_multipleWords_allCharsPreserved() {
        val text = "one two three four five six"
        val result = CommentInlayRenderer.wrapLines(text, 80, measure)

        assertEquals(text, result.joinToString(" "))
    }

    fun testWrapLines_emptyText_returnsSingleEmpty() {
        val result = CommentInlayRenderer.wrapLines("", 200, measure)

        assertEquals(listOf(""), result)
    }

    fun testWrapLines_zeroMaxWidth_returnsAsIs() {
        val result = CommentInlayRenderer.wrapLines("hello world", 0, measure)

        assertEquals(listOf("hello world"), result)
    }
}
