package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentAnchorRegistry
import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Icon

class CommentInlayManager(
    private val editor: EditorEx,
    private val store: CommentStore,
    private val file: String,
    parentDisposable: Disposable,
    private val initiallyExpanded: (PreflightComment) -> Boolean = { !it.resolved }
) : Disposable {

    private val anchorRegistry = editor.project?.service<CommentAnchorRegistry>()
    private val inlays = mutableMapOf<String, Inlay<*>>()
    private val lineHighlighters = mutableListOf<RangeHighlighter>()
    private val gutterIconHighlighters = mutableListOf<RangeHighlighter>()
    private val ownDisposable = Disposer.newDisposable().also { Disposer.register(parentDisposable, it) }
    private var updatingInlays = false
    private val expandedState = mutableMapOf<String, Boolean>()

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
                // handleClick may call refresh(), which mutates `inlays` — iterate a snapshot.
                for ((_, inlay) in inlays.toList()) {
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
        gutterIconHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        gutterIconHighlighters.clear()
        store.getCommentsForFile(file).forEach { anchorRegistry?.unregister(editor.document, it.id) }
    }

    fun refresh() {
        lineHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        lineHighlighters.clear()
        gutterIconHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        gutterIconHighlighters.clear()
        inlays.values.forEach { it.dispose() }
        inlays.clear()
        val lastLine = (editor.document.lineCount - 1).coerceAtLeast(0)
        store.getCommentsForFile(file).forEach { comment ->
            anchorRegistry?.register(editor.document, comment)
            if (anchorRegistry?.isOrphaned(comment.id) == true) return@forEach

            val liveLine = anchorRegistry?.currentLine(editor.document, comment.id)
            val renderLine = (liveLine ?: comment.line).coerceIn(0, lastLine)
            val isExpanded = expandedState.getOrPut(comment.id) { initiallyExpanded(comment) }
            if (isExpanded) {
                val offset = editor.document.getLineEndOffset(renderLine)
                val renderer = CommentInlayRenderer(comment, renderLine, this) { id ->
                    expandedState[id] = false
                    refresh()
                }
                val inlay = editor.inlayModel.addBlockElement(
                    offset,
                    InlayProperties().showAbove(false),
                    renderer
                ) ?: return@forEach
                inlays[comment.id] = inlay
            }
            gutterIconHighlighters += addToggleGutterIcon(comment, renderLine, isExpanded)

            val attrs = TextAttributes(null, JBColor(Color(255, 243, 170), Color(90, 75, 15)), null, null, Font.PLAIN)
            val span = comment.line - (comment.startLine ?: comment.line)
            val hlStart = (renderLine - span).coerceIn(0, renderLine)
            for (lineNum in hlStart..renderLine) {
                lineHighlighters += editor.markupModel.addLineHighlighter(lineNum, HighlighterLayer.SELECTION - 1, attrs)
            }
        }
    }

    private fun addToggleGutterIcon(comment: PreflightComment, renderLine: Int, isExpanded: Boolean): RangeHighlighter {
        val highlighter = editor.markupModel.addLineHighlighter(renderLine, HighlighterLayer.ADDITIONAL_SYNTAX, null)
        val icon: Icon = CommentStateIcon(comment.resolved, isExpanded)
        highlighter.gutterIconRenderer = object : GutterIconRenderer() {
            override fun getIcon(): Icon = icon
            override fun getTooltipText(): String = comment.comment.lines().first().take(80)
            override fun isNavigateAction() = true
            override fun equals(other: Any?) = other === this
            override fun hashCode() = System.identityHashCode(this)
            override fun getClickAction() = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    expandedState[comment.id] = !isExpanded
                    refresh()
                }
            }
        }
        return highlighter
    }

    fun collapseAll() {
        store.getCommentsForFile(file).forEach { expandedState[it.id] = false }
        refresh()
    }

    fun onReply(id: String, text: String) {
        store.addReply(id, text)
        refresh()
    }

    fun onEditComment(id: String, newText: String) {
        store.editComment(id, newText)
        refresh()
    }

    fun onEditReply(id: String, replyIndex: Int, newText: String) {
        store.editReply(id, replyIndex, newText)
        refresh()
    }

    fun onResolve(id: String) {
        store.resolveComment(id)
        refresh()
    }

    fun onReopen(id: String) {
        store.reopenComment(id)
        refresh()
    }

    fun onDelete(id: String) {
        store.removeComment(id)
        anchorRegistry?.unregister(editor.document, id)
        refresh()
    }
}
