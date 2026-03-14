package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import javax.swing.JButton
import javax.swing.JCheckBox
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

        panel.refresh(emptyList(), emptyList())

        assertFalse(panel.isVisible)
    }

    fun testRefresh_showsWhenStaleCommentsPresent() {
        val panel = OutdatedCommentsPanel(store) {}

        panel.refresh(listOf(PreflightComment("src/Gone.kt", 5, "Old")), emptyList())

        assertTrue(panel.isVisible)
        assertEquals(1, panel.listPanel.componentCount)
    }

    fun testDelete_removesFromStoreAndCallsCallback() {
        store.addComment(PreflightComment("src/Gone.kt", 5, "Old"))
        var callbackInvoked = false
        val panel = OutdatedCommentsPanel(store) { callbackInvoked = true }
        panel.refresh(store.getComments(), store.getComments())

        val row = panel.listPanel.getComponent(0) as JPanel
        val button = (0 until row.componentCount)
            .map { row.getComponent(it) }
            .filterIsInstance<JButton>()
            .first()
        button.doClick()

        assertTrue(store.getComments().isEmpty())
        assertTrue(callbackInvoked)
    }

    fun testShowAllCheckbox_showsAllBranchComments() {
        store.currentBranchOverride = "feature"
        store.addComment(PreflightComment("src/Gone.kt", 1, "on feature"))
        store.currentBranchOverride = "main"
        store.addComment(PreflightComment("src/Gone.kt", 2, "on main"))

        val currentBranchStale = listOf(PreflightComment("src/Gone.kt", 2, "on main", branch = "main"))
        val allBranchStale = listOf(
            PreflightComment("src/Gone.kt", 1, "on feature", branch = "feature"),
            PreflightComment("src/Gone.kt", 2, "on main", branch = "main")
        )
        val panel = OutdatedCommentsPanel(store) {}
        panel.refresh(currentBranchStale, allBranchStale)

        assertEquals(1, panel.listPanel.componentCount)

        val checkbox = findCheckbox(panel)
        checkNotNull(checkbox).doClick()

        assertEquals(2, panel.listPanel.componentCount)
    }

    fun testShowAllCheckbox_defaultShowsCurrentBranchOnly() {
        val currentBranchStale = listOf(PreflightComment("src/Gone.kt", 1, "current"))
        val allBranchStale = listOf(
            PreflightComment("src/Gone.kt", 1, "current"),
            PreflightComment("src/Other.kt", 2, "other branch")
        )
        val panel = OutdatedCommentsPanel(store) {}

        panel.refresh(currentBranchStale, allBranchStale)

        assertEquals(1, panel.listPanel.componentCount)
    }

    private fun findCheckbox(panel: OutdatedCommentsPanel): JCheckBox? {
        for (i in 0 until panel.componentCount) {
            val comp = panel.getComponent(i)
            if (comp is JPanel) {
                for (j in 0 until comp.componentCount) {
                    val inner = comp.getComponent(j)
                    if (inner is JCheckBox) return inner
                }
            }
        }
        return null
    }
}
