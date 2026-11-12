package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

class OutdatedCommentsPanel(
    private val store: CommentStore,
    private val onCommentClosed: () -> Unit
) : JPanel(BorderLayout()) {

    internal val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        val headerRow = JPanel(BorderLayout()).apply {
            add(JBLabel("Outdated Comments").apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.WEST)
        }
        add(headerRow, BorderLayout.NORTH)
        add(JBScrollPane(listPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER)
        isVisible = false
    }

    fun refresh(staleComments: List<PreflightComment>) {
        listPanel.removeAll()
        staleComments.forEach { comment -> listPanel.add(createRow(comment)) }
        listPanel.revalidate()
        listPanel.repaint()
        isVisible = staleComments.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun createRow(comment: PreflightComment): JPanel =
        JPanel(BorderLayout()).apply {
            // Stored lines are 0-based; the label shows them as editors do.
            val location = "${comment.file.substringAfterLast('/')}:${comment.line + 1}"
            add(
                JBLabel("$location – ${comment.comment.lineSequence().first()}").apply {
                    border = JBUI.Borders.empty(2, 8)
                    toolTipText = "${comment.file}:${comment.line + 1} – ${comment.comment}"
                },
                BorderLayout.CENTER
            )
            add(
                JButton("Delete").apply {
                    addActionListener {
                        store.removeComment(comment.id)
                        onCommentClosed()
                    }
                },
                BorderLayout.EAST
            )
        }
}
