package com.forcepushdev.preflight.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CommentAnchorTest : BasePlatformTestCase() {

    private fun document(text: String): Document = EditorFactory.getInstance().createDocument(text)

    private fun write(action: () -> Unit) = WriteCommandAction.runWriteCommandAction(project, action)

    fun testCurrentLine_initialPosition() {
        val doc = document("a\nb\nc\n")

        val anchor = CommentAnchor(doc, 1, 1)

        assertEquals(1, anchor.currentLine)
        assertFalse(anchor.isOrphaned)
    }

    fun testCurrentLine_shiftsDown_whenTextInsertedAbove() {
        val doc = document("a\nb\nc\n")
        val anchor = CommentAnchor(doc, 1, 1) // anchored on "b"

        write { doc.insertString(0, "new line\n") }

        assertEquals(2, anchor.currentLine)
        assertFalse(anchor.isOrphaned)
    }

    fun testCurrentLine_staysPut_whenTextChangedBelow() {
        val doc = document("a\nb\nc\n")
        val anchor = CommentAnchor(doc, 1, 1) // anchored on "b"

        write { doc.insertString(doc.textLength, "more text\n") }

        assertEquals(1, anchor.currentLine)
        assertFalse(anchor.isOrphaned)
    }

    fun testIsOrphaned_whenAnchoredLineContentFullyDeleted() {
        val doc = document("a\nb\nc\n")
        val anchor = CommentAnchor(doc, 1, 1) // anchored on "b"
        val lineStart = doc.getLineStartOffset(1)
        val lineEnd = doc.getLineEndOffset(1)

        write { doc.deleteString(lineStart, lineEnd) }

        assertTrue(anchor.isOrphaned)
        assertNull(anchor.currentLine)
    }

    fun testNotOrphaned_whenLineMergedButContentSurvives() {
        val doc = document("a\nb\nc\n")
        val anchor = CommentAnchor(doc, 1, 1) // anchored on "b"

        // Delete just the newline before "b", merging it onto line 0 as "ab"
        write { doc.deleteString(doc.getLineEndOffset(0), doc.getLineEndOffset(0) + 1) }

        assertFalse(anchor.isOrphaned)
        assertEquals(0, anchor.currentLine)
    }

    fun testNotOrphaned_forOriginallyEmptyLine() {
        val doc = document("a\n\nc\n") // line 1 is empty

        val anchor = CommentAnchor(doc, 1, 1)

        assertFalse(anchor.isOrphaned)
        assertEquals(1, anchor.currentLine)
    }

    fun testCurrentLine_multiLineRange_reportsEndLine_notStartLine() {
        val doc = document("a\nb\nc\nd\ne\n")

        val anchor = CommentAnchor(doc, 1, 3) // spans lines 1-3, anchored at end line 3

        assertEquals(3, anchor.currentLine)
    }

    fun testCurrentLine_multiLineRange_shiftsWithEndLine_whenTextInsertedAbove() {
        val doc = document("a\nb\nc\nd\ne\n")
        val anchor = CommentAnchor(doc, 1, 3) // spans lines 1-3

        write { doc.insertString(0, "new line\n") }

        assertEquals(4, anchor.currentLine)
    }

    fun testCurrentStartLine_shiftsWithText_whenTextInsertedAbove() {
        val doc = document("a\nb\nc\nd\ne\n")
        val anchor = CommentAnchor(doc, 1, 3)

        write { doc.insertString(0, "new line\n") }

        assertEquals(2, anchor.currentStartLine)
        assertEquals(4, anchor.currentLine)
    }

    fun testCurrentText_returnsFullTextOfAnchoredLines() {
        val doc = document("a\nb\nc\nd\n")
        val anchor = CommentAnchor(doc, 1, 2)

        assertEquals("b\nc", anchor.currentText)
    }

    fun testCurrentText_followsEditsInsideTheAnchoredLine() {
        val doc = document("a\nb\nc\n")
        val anchor = CommentAnchor(doc, 1, 1)

        write { doc.insertString(doc.getLineEndOffset(1), "!") }

        assertEquals("b!", anchor.currentText)
    }

    fun testCurrentText_nullWhenOrphaned() {
        val doc = document("a\nb\nc\n")
        val anchor = CommentAnchor(doc, 1, 1)

        write { doc.deleteString(doc.getLineStartOffset(1), doc.getLineEndOffset(1)) }

        assertNull(anchor.currentText)
    }

    fun testLineRangeText_coercesOutOfRangeLines() {
        val doc = document("a\nb\n")

        assertEquals("b\n", doc.lineRangeText(1, 9))
    }

    fun testCurrentLine_survivesWholeTextReplace_whenSurroundingLinesUnchanged() {
        val doc = document("a\nb\nc\nd\n")
        val anchor = CommentAnchor(doc, 2, 2) // anchored on "c"

        // Simulates an external tool (e.g. an agent) rewriting the file on disk and IntelliJ reloading it.
        write { doc.setText("x\ny\na\nb\nc\nd\n") }

        assertFalse(anchor.isOrphaned)
        assertEquals(4, anchor.currentLine)
    }

    fun testEditInsideRange_newLineBetweenAnchoredLines_growsRange() {
        val doc = document("a\nb\nc\nd\ne\n")
        val anchor = CommentAnchor(doc, 1, 3) // b..d

        write { doc.insertString(doc.getLineStartOffset(2), "new\n") } // between b and c

        assertFalse(anchor.isOrphaned)
        assertEquals(1, anchor.currentStartLine)
        assertEquals(4, anchor.currentLine)
        assertEquals("b\nnew\nc\nd", anchor.currentText)
    }

    fun testEditInsideRange_enterInMiddleOfSingleLine_growsRange() {
        val doc = document("a\nfoo bar\nc\n")
        val anchor = CommentAnchor(doc, 1, 1)

        write { doc.insertString(doc.getLineStartOffset(1) + 3, "\n") } // "foo" / " bar"

        assertEquals(1, anchor.currentStartLine)
        assertEquals(2, anchor.currentLine)
        assertEquals("foo\n bar", anchor.currentText)
    }

    fun testEditInsideRange_typingInsideLine_keepsRange() {
        val doc = document("a\nfoo\nc\n")
        val anchor = CommentAnchor(doc, 1, 1)

        write { doc.insertString(doc.getLineStartOffset(1) + 1, "XYZ") }

        assertEquals(1, anchor.currentStartLine)
        assertEquals(1, anchor.currentLine)
        assertEquals("fXYZoo", anchor.currentText)
    }

    fun testEditInsideRange_multiLinePaste_growsRange() {
        val doc = document("a\nb\nc\nd\n")
        val anchor = CommentAnchor(doc, 1, 2) // b..c

        write { doc.insertString(doc.getLineEndOffset(1), "\nx\ny\nz") } // paste after b

        assertEquals(1, anchor.currentStartLine)
        assertEquals(5, anchor.currentLine)
        assertEquals("b\nx\ny\nz\nc", anchor.currentText)
    }

    fun testEditInsideRange_atFirstLineStart_movesWholeRange() {
        val doc = document("a\nb\nc\nd\n")
        val anchor = CommentAnchor(doc, 1, 2)

        write { doc.insertString(doc.getLineStartOffset(1), "new\n") } // Enter at column 0 of "b"

        assertEquals(2, anchor.currentStartLine)
        assertEquals(3, anchor.currentLine)
        assertEquals("b\nc", anchor.currentText)
    }

    fun testEditInsideRange_appendNewLineAfterLastAnchoredLine_doesNotGrowRange() {
        val doc = document("a\nb\nc\nd\n")
        val anchor = CommentAnchor(doc, 1, 2)

        write { doc.insertString(doc.getLineEndOffset(2), "\nafter") }

        assertEquals(1, anchor.currentStartLine)
        assertEquals(2, anchor.currentLine)
        assertEquals("b\nc", anchor.currentText)
    }
}
