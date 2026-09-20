package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class CommentInlayManagerTest : BasePlatformTestCase() {

    private lateinit var store: CommentStore

    override fun setUp() {
        super.setUp()
        store = project.service<CommentStore>()
    }

    override fun tearDown() {
        project.basePath?.let { File("$it/.preflight").deleteRecursively() }
        super.tearDown()
    }

    private fun editorWithComment(text: String, startLine: Int, endLine: Int): Pair<EditorEx, CommentInlayManager> {
        myFixture.configureByText("Foo.txt", text)
        val editor = myFixture.editor as EditorEx
        store.addComment(
            PreflightComment("Foo.txt", endLine, "c", startLine = startLine, anchorText = "x")
        )
        return editor to CommentInlayManager(editor, store, "Foo.txt", testRootDisposable, redrawDelayMs = 30)
    }

    /** Lines that currently carry the comment's yellow background. */
    private fun highlightedLines(editor: EditorEx): Set<Int> =
        editor.markupModel.allHighlighters
            .filter { it.getTextAttributes(null)?.backgroundColor != null }
            .flatMap { editor.document.getLineNumber(it.startOffset)..editor.document.getLineNumber(it.endOffset) }
            .toSet()

    private fun insert(editor: EditorEx, offset: Int, text: String) =
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(offset, text) }

    fun testHighlight_coversAnchoredLines_initially() {
        val (editor, _) = editorWithComment("a\nb\nc\nd\ne\n", startLine = 1, endLine = 3)

        assertEquals(setOf(1, 2, 3), highlightedLines(editor))
    }

    fun testHighlight_followsShiftFromEditAbove() {
        val (editor, _) = editorWithComment("a\nb\nc\nd\ne\n", startLine = 1, endLine = 3)

        insert(editor, 0, "new\n")

        assertEquals(setOf(2, 3, 4), highlightedLines(editor))
    }

    fun testHighlight_includesLineAddedInsideRange_withoutRefresh() {
        val (editor, _) = editorWithComment("a\nb\nc\nd\ne\n", startLine = 1, endLine = 3)

        insert(editor, editor.document.getLineStartOffset(2), "new\n") // between b and c

        assertEquals(setOf(1, 2, 3, 4), highlightedLines(editor))
    }

    fun testHighlight_includesLineAddedInsideRange_afterRefreshBeforeFlush() {
        val (editor, manager) = editorWithComment("a\nb\nc\nd\ne\n", startLine = 1, endLine = 3)

        // The store still holds the old range here: the debounced write-back hasn't run yet.
        insert(editor, editor.document.getLineStartOffset(2), "new\n")
        manager.refresh()

        assertEquals(setOf(1, 2, 3, 4), highlightedLines(editor))
    }

    fun testHighlight_shrinksWhenLineInsideRangeIsDeleted_afterRefresh() {
        val (editor, manager) = editorWithComment("a\nb\nc\nd\ne\n", startLine = 1, endLine = 3)

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.deleteString(editor.document.getLineStartOffset(2), editor.document.getLineStartOffset(3))
        }
        manager.refresh()

        assertEquals(setOf(1, 2), highlightedLines(editor))
    }

    private fun inlayLines(editor: EditorEx): List<Int> =
        editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
            .map { editor.document.getLineNumber(it.offset) }

    private fun gutterIconLines(editor: EditorEx): List<Int> =
        editor.markupModel.allHighlighters
            .filter { it.gutterIconRenderer != null }
            .map { editor.document.getLineNumber(it.startOffset) }

    fun testInlayAndIcon_stayOnLastAnchoredLine_whenLineAddedInsideRange() {
        val (editor, manager) = editorWithComment("a\nb\nc\nd\ne\n", startLine = 1, endLine = 3)

        insert(editor, editor.document.getLineStartOffset(2), "new\n")
        manager.refresh()

        assertEquals(listOf(4), inlayLines(editor))
        assertEquals(listOf(4), gutterIconLines(editor))
    }

    fun testInlayAndIcon_followAnchorEnd_whenEnterPressedInMiddleOfLastAnchoredLine() {
        val (editor, manager) = editorWithComment("a\nb\ncc\nd\n", startLine = 1, endLine = 2)

        insert(editor, editor.document.getLineStartOffset(2) + 1, "\n") // "c" / "c"
        manager.refresh()

        assertEquals(setOf(1, 2, 3), highlightedLines(editor))
        assertEquals(listOf(3), inlayLines(editor))
        assertEquals(listOf(3), gutterIconLines(editor))
    }

    fun testInlay_notPushedBelowNewLine_whenEnterPressedAtEndOfLastAnchoredLine() {
        val (editor, _) = editorWithComment("a\nb\nc\nd\n", startLine = 1, endLine = 2)

        insert(editor, editor.document.getLineEndOffset(2), "\nafter") // new line typed below the comment

        assertEquals(listOf(2), inlayLines(editor))
        assertEquals(listOf(2), gutterIconLines(editor))
        assertEquals(setOf(1, 2), highlightedLines(editor))
    }

    fun testInlayAndIcon_followAnchorEnd_whenEnterPressedInMiddleOfLastAnchoredLine_afterTypingPauses() {
        val (editor, _) = editorWithComment("a\nb\ncc\nd\n", startLine = 1, endLine = 2)

        insert(editor, editor.document.getLineStartOffset(2) + 1, "\n")
        Thread.sleep(200)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(setOf(1, 2, 3), highlightedLines(editor))
        assertEquals(listOf(3), inlayLines(editor))
        assertEquals(listOf(3), gutterIconLines(editor))
    }
}
