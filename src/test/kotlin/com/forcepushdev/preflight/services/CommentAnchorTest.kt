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
}
