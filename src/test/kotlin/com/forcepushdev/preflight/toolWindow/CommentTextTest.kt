package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.toolWindow.CommentText.Code
import com.forcepushdev.preflight.toolWindow.CommentText.Prose
import com.forcepushdev.preflight.toolWindow.CommentText.Segment
import junit.framework.TestCase

class CommentTextTest : TestCase() {

    private val measure: (Segment) -> Int = { it.text.length * 8 }

    fun testParse_plainText_singleProseLine() {
        assertEquals(listOf(Prose(listOf(Segment("hello world")))), CommentText.parse("hello world"))
    }

    fun testParse_empty_singleEmptyLine() {
        assertEquals(listOf(Prose(emptyList())), CommentText.parse(""))
    }

    fun testParse_inlineCode_markedAsCodeSpan() {
        val prose = CommentText.parse("call `toEntity()` here").single() as Prose

        assertEquals(listOf(Segment("call "), Segment("toEntity()", 0), Segment(" here")), prose.segments)
    }

    fun testParse_lineBreaks_becomeSeparateLines() {
        assertEquals(2, CommentText.parse("one\ntwo").size)
    }

    fun testParse_fencedBlock_dropsLanguageTagAndKeepsLines() {
        val blocks = CommentText.parse("Vorschlag:\n```kotlin\n@Column(updatable = false)\nvar x: String?\n```\nDanach")

        assertEquals(
            listOf(
                Prose(listOf(Segment("Vorschlag:"))),
                Code(listOf("@Column(updatable = false)", "var x: String?")),
                Prose(listOf(Segment("Danach")))
            ),
            blocks
        )
    }

    fun testParse_fenceRightAfterText_stillABlock() {
        val blocks = CommentText.parse("absichern:```kotlin\nval a = 1\n```")

        assertEquals(listOf(Prose(listOf(Segment("absichern:"))), Code(listOf("val a = 1"))), blocks)
    }

    fun testParse_unclosedFence_restIsCode() {
        assertEquals(Code(listOf("val a = 1")), CommentText.parse("```\nval a = 1").last())
    }

    fun testWrap_shortLine_singleRow() {
        assertEquals(listOf(listOf(Segment("hello"))), CommentText.wrap(listOf(Segment("hello")), 200, measure))
    }

    fun testWrap_longLine_rowsFitWidth() {
        val rows = CommentText.wrap(listOf(Segment("one two three four five")), 80, measure)

        assertTrue(rows.size > 1)
        rows.forEach { row -> assertTrue(row.sumOf(measure) <= 80) }
        assertEquals("one two three four five", rows.joinToString(" ") { row -> row.joinToString("") { it.text } })
    }

    fun testWrap_spaceInsideCodeSpan_staysCode() {
        val rows = CommentText.wrap(listOf(Segment("see "), Segment("a = b", 0), Segment(".")), 500, measure)

        assertEquals(listOf(listOf(Segment("see "), Segment("a = b", 0), Segment("."))), rows)
    }

    fun testWrap_codeSpanSplitAcrossRows() {
        val rows = CommentText.wrap(listOf(Segment("aaaa bbbb", 0)), 40, measure)

        assertEquals(listOf(listOf(Segment("aaaa", 0)), listOf(Segment("bbbb", 0))), rows)
    }

    fun testWrap_singleLongWord_ownRow() {
        assertEquals(1, CommentText.wrap(listOf(Segment("verylongwordthatcannotbesplit")), 50, measure).size)
    }

    fun testWrapCode_breaksAtCharacters() {
        assertEquals(listOf("abcd", "efgh", "ij"), CommentText.wrapCode("abcdefghij", 32) { it.length * 8 })
    }

    fun testWrapCode_emptyLine_kept() {
        assertEquals(listOf(""), CommentText.wrapCode("", 32) { it.length * 8 })
    }
}
