package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel

class OutdatedCommentsPanel(
    private val store: CommentStore,
    private val onCommentClosed: () -> Unit
) : JPanel(BorderLayout()) {

    internal val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    private var currentBranchStale: List<PreflightComment> = emptyList()
    private var allBranchStale: List<PreflightComment> = emptyList()

    private val showAllCheckbox = JCheckBox("Show all branches").apply {
        isOpaque = false
        addActionListener { rebuildList() }
    }

    init {
        val headerRow = JPanel(BorderLayout()).apply {
            add(JBLabel("Outdated Comments").apply { border = JBUI.Borders.empty(4, 8) }, BorderLayout.WEST)
            add(showAllCheckbox, BorderLayout.EAST)
        }
        add(headerRow, BorderLayout.NORTH)
        add(JBScrollPane(listPanel), BorderLayout.CENTER)
        isVisible = false
    }

    fun refresh(currentBranchStale: List<PreflightComment>, allBranchStale: List<PreflightComment>) {
        this.currentBranchStale = currentBranchStale
        this.allBranchStale = allBranchStale
        rebuildList()
    }

    private fun rebuildList() {
        val toShow = if (showAllCheckbox.isSelected) allBranchStale else currentBranchStale
        listPanel.removeAll()
        toShow.forEach { comment -> listPanel.add(createRow(comment)) }
        listPanel.revalidate()
        listPanel.repaint()
        isVisible = toShow.isNotEmpty()
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
                        store.removeComment(comment.file, comment.line)
                        onCommentClosed()
                    }
                },
                BorderLayout.EAST
            )
        }
}
