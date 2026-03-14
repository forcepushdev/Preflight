package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentStore
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

class CommentInlayManager(
    private val editor: EditorEx,
    private val store: CommentStore,
    private val file: String,
    parentDisposable: Disposable
) : Disposable {

    private val inlays = mutableMapOf<Int, Inlay<*>>()
    private val lineHighlighters = mutableListOf<RangeHighlighter>()
    private val ownDisposable = Disposer.newDisposable().also { Disposer.register(parentDisposable, it) }
    private var updatingInlays = false

    init {
        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (updatingInlays) return
                updatingInlays = true
                try {
                    inlays.values.forEach { it.update() }
                } finally {
                    updatingInlays = false
                }
            }
        }
        editor.contentComponent.addComponentListener(resizeListener)
        Disposer.register(ownDisposable) { editor.contentComponent.removeComponentListener(resizeListener) }

        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val point = event.mouseEvent.point
                for ((_, inlay) in inlays) {
                    val bounds = inlay.bounds ?: continue
                    if (bounds.contains(point)) {
                        val renderer = inlay.renderer as? CommentInlayRenderer ?: continue
                        renderer.handleClick(point, inlay)
                        event.consume()
                        break
                    }
                }
            }
        }, ownDisposable)
        refresh()
    }

    override fun dispose() {
        inlays.values.forEach { it.dispose() }
        inlays.clear()
        lineHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        lineHighlighters.clear()
    }

    fun refresh() {
        lineHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        lineHighlighters.clear()
        inlays.values.forEach { it.dispose() }
        inlays.clear()
        store.getCommentsForFile(file).forEach { comment ->
            if (comment.line >= editor.document.lineCount) return@forEach
            val offset = editor.document.getLineEndOffset(comment.line)
            val renderer = CommentInlayRenderer(comment, comment.line, this)
            val inlay = editor.inlayModel.addBlockElement(
                offset,
                InlayProperties().showAbove(false),
                renderer
            ) ?: return@forEach
            inlays[comment.line] = inlay
            val attrs = TextAttributes(null, JBColor(Color(255, 243, 170), Color(90, 75, 15)), null, null, Font.PLAIN)
            val hlStart = comment.startLine ?: comment.line
            for (lineNum in hlStart..comment.line) {
                lineHighlighters += editor.markupModel.addLineHighlighter(lineNum, HighlighterLayer.SELECTION - 1, attrs)
            }
        }
    }

    fun onReply(line: Int, text: String) {
        store.addReply(file, line, text)
        refresh()
    }

    fun onEditComment(line: Int, newText: String) {
        store.editComment(file, line, newText)
        refresh()
    }

    fun onEditReply(line: Int, replyIndex: Int, newText: String) {
        store.editReply(file, line, replyIndex, newText)
        refresh()
    }

    fun onResolve(line: Int) {
        store.resolveComment(file, line)
        refresh()
    }

    fun onReopen(line: Int) {
        store.reopenComment(file, line)
        refresh()
    }

    fun onDelete(line: Int) {
        store.removeComment(file, line)
        inlays.remove(line)?.dispose()
        val hlStart = store.getCommentsForFile(file).firstOrNull { it.line == line }?.startLine ?: line
        lineHighlighters.removeAll { hl ->
            val hlLine = editor.document.getLineNumber(hl.startOffset)
            if (hlLine in hlStart..line) { editor.markupModel.removeHighlighter(hl); true } else false
        }
        refresh()
    }
}
