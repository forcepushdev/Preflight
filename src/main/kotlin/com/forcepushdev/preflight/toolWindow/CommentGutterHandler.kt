package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import javax.swing.Icon

class CommentGutterHandler(
    private val store: CommentStore,
    private val onCommentAdded: () -> Unit
) {
    private var selectionHighlighter: RangeHighlighter? = null
    private val commentHighlighters = mutableListOf<RangeHighlighter>()
    private var currentEditor: EditorEx? = null
    private var currentFile: String = ""
    private var selectionStartLine: Int = 0

    fun attach(editor: EditorEx, file: String, disposable: Disposable) {
        val isNewEditor = currentEditor != editor
        if (isNewEditor) currentEditor?.let { detach(it) }
        currentEditor = editor
        currentFile = file
        if (isNewEditor) {
            editor.selectionModel.addSelectionListener(object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    selectionHighlighter?.let { editor.markupModel.removeHighlighter(it) }
                    selectionHighlighter = null
                    if (e.newRange.length > 0) {
                        val startLine = editor.document.getLineNumber(e.newRange.startOffset)
                        val endLine = editor.document.getLineNumber(
                            (e.newRange.endOffset - 1).coerceAtLeast(0)
                        )
                        selectionStartLine = startLine
                        selectionHighlighter = addPlusHighlighter(editor, startLine, endLine)
                    }
                }
            }, disposable)
        }
    }

    fun detach(editor: EditorEx) {
        selectionHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        selectionHighlighter = null
        clearCommentHighlighters(editor)
        if (currentEditor == editor) currentEditor = null
    }

    private fun addPlusHighlighter(editor: EditorEx, startLine: Int, endLine: Int): RangeHighlighter {
        val h = editor.markupModel.addLineHighlighter(endLine, HighlighterLayer.ADDITIONAL_SYNTAX, null)
        h.gutterIconRenderer = object : GutterIconRenderer() {
            override fun getIcon(): Icon = AllIcons.General.Add
            override fun isNavigateAction() = true
            override fun equals(other: Any?) = other === this
            override fun hashCode() = System.identityHashCode(this)
            override fun getClickAction() = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    CommentInputPopup.show(editor, endLine) { text ->
                        store.addComment(PreflightComment(currentFile, endLine, text, startLine = startLine))
                        onCommentAdded()
                    }
                }
            }
        }
        return h
    }

    private fun clearCommentHighlighters(editor: EditorEx) {
        commentHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        commentHighlighters.clear()
    }
}
