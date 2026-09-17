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
        add(JBScrollPane(listPanel), BorderLayout.CENTER)
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
            add(
                JBLabel("${comment.file}:${comment.line} – ${comment.comment}").apply {
                    border = JBUI.Borders.empty(2, 8)
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
