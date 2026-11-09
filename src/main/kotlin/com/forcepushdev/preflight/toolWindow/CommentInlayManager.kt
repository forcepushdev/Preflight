package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.CommentAnchorRegistry
import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Icon
import javax.swing.SwingUtilities

class CommentInlayManager(
    private val editor: EditorEx,
    private val store: CommentStore,
    private val file: String,
    parentDisposable: Disposable,
    private val initiallyExpanded: (PreflightComment) -> Boolean = { !it.resolved },
    private val redrawDelayMs: Int = 250
) : Disposable {

    private val anchorRegistry = editor.project?.service<CommentAnchorRegistry>()
    private val inlays = mutableMapOf<String, Inlay<*>>()
    private val lineHighlighters = mutableListOf<RangeHighlighter>()
    private val gutterIconHighlighters = mutableListOf<RangeHighlighter>()
    private val ownDisposable = Disposer.newDisposable().also { Disposer.register(parentDisposable, it) }
    private var updatingInlays = false
    private val expandedState = mutableMapOf<String, Boolean>()
    private val redrawAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, ownDisposable)
    private var selectingInlay: Inlay<*>? = null

    init {
        // Highlight, inlay and gutter icon sit at offsets that shift with edits, but the line the
        // comment *ends* on can change (Enter or a paste inside the block) — redraw once typing pauses.
        editor.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                redrawAlarm.cancelAllRequests()
                redrawAlarm.addRequest({ refresh() }, redrawDelayMs)
            }
        }, ownDisposable)

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
        // The viewport is what the inlay width follows; the content component does not resize when
        // the editor narrows while a line is wider than the view.
        val viewport = editor.scrollPane.viewport
        viewport.addComponentListener(resizeListener)
        Disposer.register(ownDisposable) { viewport.removeComponentListener(resizeListener) }

        editor.putUserData(MANAGER_KEY, this)
        Disposer.register(ownDisposable) { if (editor.getUserData(MANAGER_KEY) === this) editor.putUserData(MANAGER_KEY, null) }

        // A press on comment text is consumed, so the editor neither moves the caret nor starts its own
        // selection; the drag then selects inside the card. Presses on buttons stay unconsumed, as only
        // those produce the mouseClicked the buttons react to.
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mousePressed(event: EditorMouseEvent) {
                selectingInlay = null
                val point = event.mouseEvent.point
                val hit = inlays.values.firstOrNull { it.bounds?.contains(point) == true }
                clearSelections(except = hit)
                if (hit == null || !SwingUtilities.isLeftMouseButton(event.mouseEvent)) return
                val renderer = hit.renderer as? CommentInlayRenderer ?: return
                if (renderer.startSelection(relative(point, hit) ?: return, event.mouseEvent.clickCount)) {
                    selectingInlay = hit
                    event.consume()
                } else {
                    renderer.clearSelection()
                }
                hit.repaint()
            }

            override fun mouseReleased(event: EditorMouseEvent) {
                selectingInlay = null
            }

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

        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseDragged(event: EditorMouseEvent) {
                val inlay = selectingInlay ?: return
                val renderer = inlay.renderer as? CommentInlayRenderer ?: return
                renderer.extendSelection(relative(event.mouseEvent.point, inlay) ?: return)
                inlay.repaint()
                event.consume()
            }
        }, ownDisposable)

        // Selecting code with the keyboard must not leave a comment selection that Copy would prefer.
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                if (!e.newRange.isEmpty) clearSelections()
            }
        }, ownDisposable)
        refresh()
    }

    private fun relative(point: Point, inlay: Inlay<*>): Point? =
        inlay.bounds?.let { Point(point.x - it.x, point.y - it.y) }

    private fun clearSelections(except: Inlay<*>? = null) {
        for (inlay in inlays.values) {
            if (inlay === except) continue
            if ((inlay.renderer as? CommentInlayRenderer)?.clearSelection() == true) inlay.repaint()
        }
    }

    /** The comment text selected in one of this editor's cards, if any. */
    fun selectedText(): String? =
        inlays.values.firstNotNullOfOrNull { (it.renderer as? CommentInlayRenderer)?.selectedText() }

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

            val liveRange = anchorRegistry?.currentRange(editor.document, comment.id)
            val renderLine = (liveRange?.last ?: comment.line).coerceIn(0, lastLine)
            val startLine = (liveRange?.first ?: comment.startLine ?: comment.line).coerceIn(0, renderLine)
            val isExpanded = expandedState.getOrPut(comment.id) { initiallyExpanded(comment) }
            if (isExpanded) {
                val offset = editor.document.getLineEndOffset(renderLine)
                val renderer = CommentInlayRenderer(comment, renderLine, startLine, this) { id ->
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

            // One range highlighter instead of a line highlighter per line: it is a range marker,
            // so lines typed or pasted inside the commented block are covered without a refresh.
            val attrs = TextAttributes(null, COMMENTED_LINES_BACKGROUND, null, null, Font.PLAIN)
            lineHighlighters += editor.markupModel.addRangeHighlighter(
                editor.document.getLineStartOffset(startLine),
                editor.document.getLineEndOffset(renderLine),
                HighlighterLayer.SELECTION - 1,
                attrs,
                HighlighterTargetArea.LINES_IN_RANGE
            ).apply { customRenderer = AccentBarRenderer }
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

    fun onDeleteReply(id: String, replyIndex: Int) {
        store.removeReply(id, replyIndex)
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

    // Paints the accent bar along the left edge of the commented lines, at the start of the text area.
    private object AccentBarRenderer : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, g: Graphics) {
            val top = editor.offsetToXY(highlighter.startOffset).y
            val bottom = editor.offsetToXY(highlighter.endOffset).y + editor.lineHeight
            g.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
            g.fillRect(0, top, JBUI.scale(3), bottom - top)
        }
    }

    companion object {
        private val MANAGER_KEY = Key.create<CommentInlayManager>("preflight.commentInlayManager")

        fun forEditor(editor: Editor): CommentInlayManager? = editor.getUserData(MANAGER_KEY)

        private val COMMENTED_LINES_BACKGROUND = JBColor(Color(232, 240, 254), Color(40, 52, 77))
    }
}
