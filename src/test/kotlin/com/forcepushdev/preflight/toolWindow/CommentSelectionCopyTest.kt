package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File

class CommentSelectionCopyTest : BasePlatformTestCase() {

    override fun tearDown() {
        project.basePath?.let { File("$it/.preflight").deleteRecursively() }
        super.tearDown()
    }

    private fun paintedRenderer(commentText: String): Pair<EditorEx, CommentInlayRenderer> {
        myFixture.configureByText("Foo.txt", "alpha\nbeta\n")
        val editor = myFixture.editor as EditorEx
        val store = project.service<CommentStore>()
        store.addComment(PreflightComment("Foo.txt", 0, commentText, anchorText = "alpha"))
        CommentInlayManager(editor, store, "Foo.txt", testRootDisposable)
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        val renderer = inlay.renderer as CommentInlayRenderer
        val image = BufferedImage(800, 400, BufferedImage.TYPE_INT_ARGB)
        renderer.paint(inlay, image.createGraphics() as Graphics, Rectangle(0, 0, 800, inlay.heightInPixels), TextAttributes())
        return editor to renderer
    }

    private fun clipboard(): String? = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)

    // The first text row sits below the header row.
    private val onCommentText = Point(30, 45)

    fun testCopy_withCommentSelection_copiesSelectedText() {
        val (_, renderer) = paintedRenderer("see `toEntity()` here")

        assertTrue(renderer.startSelection(onCommentText, clickCount = 3))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COPY)

        assertEquals("see toEntity() here", clipboard())
    }

    fun testCopy_withoutCommentSelection_copiesEditorSelection() {
        val (editor, _) = paintedRenderer("note")

        editor.selectionModel.setSelection(0, 5)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COPY)

        assertEquals("alpha", clipboard())
    }

    fun testEditorSelection_clearsCommentSelection() {
        val (editor, renderer) = paintedRenderer("note")
        renderer.startSelection(onCommentText, clickCount = 3)

        editor.selectionModel.setSelection(0, 5)

        assertNull(renderer.selectedText())
    }
}
