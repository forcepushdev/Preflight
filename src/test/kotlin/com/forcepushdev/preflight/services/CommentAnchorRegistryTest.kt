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

    fun testFlush_writesStartLineAndAnchorText_notJustEndLine() {
        val comment = PreflightComment("Foo.kt", 3, "text", startLine = 2, anchorText = "c\nd")
        store.addComment(comment)
        val doc = document("a\nb\nc\nd\ne\n")
        registry.register(doc, comment)

        write { doc.insertString(0, "new\nnew\n") }
        waitForDebounce()

        val saved = store.getComment(comment.id)!!
        assertEquals(4, saved.startLine)
        assertEquals(5, saved.line)
        assertEquals("c\nd", saved.anchorText)
    }

    fun testFlush_updatesAnchorText_whenAnchoredLineIsEdited() {
        val comment = PreflightComment("Foo.kt", 1, "text", startLine = 1, anchorText = "b")
        store.addComment(comment)
        val doc = document("a\nb\nc\n")
        registry.register(doc, comment)

        write { doc.insertString(doc.getLineEndOffset(1), "!") }
        waitForDebounce()

        assertEquals("b!", store.getComment(comment.id)!!.anchorText)
    }

    fun testRegister_backfillsAnchorText_forLegacyComment() {
        val comment = PreflightComment("Foo.kt", 1, "text")
        store.addComment(comment)
        val doc = document("a\nb\nc\n")

        registry.register(doc, comment)
        waitForDebounce()

        val saved = store.getComment(comment.id)!!
        assertEquals("b", saved.anchorText)
        assertEquals(1, saved.startLine)
    }

    fun testRegister_relocatesByAnchorText_whenStoredLineIsStale() {
        // File was edited without IntelliJ (e.g. by an agent): 2 lines were added above "c".
        val comment = PreflightComment("Foo.kt", 2, "text", startLine = 2, anchorText = "c")
        store.addComment(comment)
        val doc = document("x\ny\na\nb\nc\nd\n")

        registry.register(doc, comment)
        waitForDebounce()

        assertEquals(4, registry.currentLine(doc, comment.id))
        val saved = store.getComment(comment.id)!!
        assertEquals(4, saved.line)
        assertEquals(4, saved.startLine)
    }

    fun testRegister_keepsStoredLine_whenAnchorTextNotFound() {
        // The commented code itself was rewritten: don't guess, don't orphan.
        val comment = PreflightComment("Foo.kt", 1, "text", startLine = 1, anchorText = "old code")
        store.addComment(comment)
        val doc = document("a\nnew code\nc\n")

        registry.register(doc, comment)

        assertEquals(1, registry.currentLine(doc, comment.id))
        assertFalse(registry.isOrphaned(comment.id))
    }

    fun testRegister_isIdempotent_andKeepsLiveAnchor_whenStoreIsStale() {
        // refresh() re-registers on every toggle/reply; inside the debounce window the store is
        // stale and must not overwrite the position the live anchor already tracked.
        val comment = PreflightComment("Foo.kt", 1, "text", startLine = 1, anchorText = "b")
        store.addComment(comment)
        val doc = document("a\nb\nc\n")
        registry.register(doc, comment)
        write { doc.insertString(0, "new\n") }

        registry.register(doc, store.getComment(comment.id)!!)

        assertEquals(2, registry.currentLine(doc, comment.id))
    }

    fun testRegister_reanchors_whenStoredLineChangedExternally() {
        val comment = PreflightComment("Foo.kt", 1, "text", startLine = 1, anchorText = "b")
        store.addComment(comment)
        val doc = document("a\nb\nc\nd\n")
        registry.register(doc, comment)

        // An agent moved the comment in comments.json.
        registry.register(doc, comment.copy(line = 3, startLine = 3, anchorText = "d"))

        assertEquals(3, registry.currentLine(doc, comment.id))
    }
}
