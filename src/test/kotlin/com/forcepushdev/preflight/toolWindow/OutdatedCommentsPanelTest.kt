package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import javax.swing.JButton
import javax.swing.JPanel

class OutdatedCommentsPanelTest : BasePlatformTestCase() {

    private lateinit var store: CommentStore

    override fun setUp() {
        super.setUp()
        store = CommentStore(project)
    }

    override fun tearDown() {
        project.basePath?.let { File("$it/.preflight").deleteRecursively() }
        super.tearDown()
    }

    fun testRefresh_hidesWhenNoStaleComments() {
        val panel = OutdatedCommentsPanel(store) {}

        panel.refresh(emptyList())

        assertFalse(panel.isVisible)
    }

    fun testRefresh_showsWhenStaleCommentsPresent() {
        val panel = OutdatedCommentsPanel(store) {}

        panel.refresh(listOf(PreflightComment("src/Gone.kt", 5, "Old")))

        assertTrue(panel.isVisible)
        assertEquals(1, panel.listPanel.componentCount)
    }

    fun testDelete_removesFromStoreAndCallsCallback() {
        val comment = PreflightComment("src/Gone.kt", 5, "Old")
        store.addComment(comment)
        var callbackInvoked = false
        val panel = OutdatedCommentsPanel(store) { callbackInvoked = true }
        panel.refresh(store.getComments())

        val row = panel.listPanel.getComponent(0) as JPanel
        val button = (0 until row.componentCount)
            .map { row.getComponent(it) }
            .filterIsInstance<JButton>()
            .first()
        button.doClick()

        assertTrue(store.getComments().isEmpty())
        assertTrue(callbackInvoked)
    }
}
