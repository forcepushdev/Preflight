package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.forcepushdev.preflight.services.lineRangeText
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
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
    private var plusHighlighter: RangeHighlighter? = null
    private var plusHighlighterStartLine: Int = -1
    private var plusHighlighterEndLine: Int = -1
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
                    updatePlusIcon(editor)
                }
            }, disposable)
            editor.caretModel.addCaretListener(object : CaretListener {
                override fun caretPositionChanged(e: CaretEvent) {
                    updatePlusIcon(editor)
                }
            }, disposable)
            updatePlusIcon(editor)
        }
    }

    fun detach(editor: EditorEx) {
        plusHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        plusHighlighter = null
        plusHighlighterStartLine = -1
        plusHighlighterEndLine = -1
        clearCommentHighlighters(editor)
        if (currentEditor == editor) currentEditor = null
    }

    private fun updatePlusIcon(editor: EditorEx) {
        val selectionModel = editor.selectionModel
        val startLine: Int
        val endLine: Int
        if (selectionModel.hasSelection()) {
            startLine = editor.document.getLineNumber(selectionModel.selectionStart)
            endLine = editor.document.getLineNumber(
                (selectionModel.selectionEnd - 1).coerceAtLeast(0)
            )
        } else {
            startLine = editor.caretModel.logicalPosition.line
            endLine = startLine
        }
        selectionStartLine = startLine
        if (startLine == plusHighlighterStartLine && endLine == plusHighlighterEndLine && plusHighlighter != null) return
        plusHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        plusHighlighter = addPlusHighlighter(editor, startLine, endLine)
        plusHighlighterStartLine = startLine
        plusHighlighterEndLine = endLine
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
                        store.addComment(
                            PreflightComment(
                                currentFile, endLine, text, startLine = startLine,
                                anchorText = editor.document.lineRangeText(startLine, endLine)
                            )
                        )
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
