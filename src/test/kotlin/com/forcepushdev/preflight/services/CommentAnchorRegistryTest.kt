package com.forcepushdev.preflight.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class CommentAnchorRegistryTest : BasePlatformTestCase() {

    private lateinit var store: CommentStore
    private lateinit var registry: CommentAnchorRegistry

    override fun setUp() {
        super.setUp()
        // CommentAnchorRegistry.flush() looks up CommentStore via project.service<>(), so the
        // test must observe the same singleton instance rather than a freestanding one.
        store = project.service<CommentStore>()
        registry = CommentAnchorRegistry(project, debounceMs = 60)
    }

    override fun tearDown() {
        project.basePath?.let { File("$it/.preflight").deleteRecursively() }
        super.tearDown()
    }

    private fun document(text: String): Document = EditorFactory.getInstance().createDocument(text)

    private fun write(action: () -> Unit) = WriteCommandAction.runWriteCommandAction(project, action)

    private fun waitForDebounce() {
        Thread.sleep(300)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testFlush_doesNotUpdateStore_beforeDebounceElapses() {
        val comment = PreflightComment("Foo.kt", 1, "text")
        store.addComment(comment)
        val doc = document("a\nb\nc\n")
        registry.register(doc, comment)

        write { doc.insertString(0, "new\n") }

        assertEquals(1, store.getComment(comment.id)!!.line)
    }

    fun testFlush_updatesStore_afterDebounceElapses() {
        val comment = PreflightComment("Foo.kt", 1, "text")
        store.addComment(comment)
        val doc = document("a\nb\nc\n")
        registry.register(doc, comment)

        write { doc.insertString(0, "new\n") }
        waitForDebounce()

        assertEquals(2, store.getComment(comment.id)!!.line)
    }

    fun testFlush_coalescesRapidEdits_lastLineWins() {
        val comment = PreflightComment("Foo.kt", 2, "text")
        store.addComment(comment)
        val doc = document("a\nb\nc\nd\n")
        registry.register(doc, comment)

        write { doc.insertString(0, "x\n") }
        write { doc.insertString(0, "y\n") }
        waitForDebounce()

        assertEquals(4, store.getComment(comment.id)!!.line)
    }

    fun testIsOrphaned_reportedAfterFlush_whenAnchoredLineDeleted() {
        val comment = PreflightComment("Foo.kt", 1, "text")
        store.addComment(comment)
        val doc = document("a\nb\nc\n")
        registry.register(doc, comment)
        val lineStart = doc.getLineStartOffset(1)
        val lineEnd = doc.getLineEndOffset(1)

        write { doc.deleteString(lineStart, lineEnd) }
        waitForDebounce()

        assertTrue(registry.isOrphaned(comment.id))
    }

    fun testIsOrphaned_false_whenAnchorUntouched() {
        val comment = PreflightComment("Foo.kt", 1, "text")
        store.addComment(comment)
        val doc = document("a\nb\nc\n")
        registry.register(doc, comment)

        write { doc.insertString(doc.textLength, "more\n") }
        waitForDebounce()

        assertFalse(registry.isOrphaned(comment.id))
    }
}
