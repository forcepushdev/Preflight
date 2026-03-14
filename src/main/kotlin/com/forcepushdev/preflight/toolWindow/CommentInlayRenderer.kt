package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints

class CommentInlayRenderer(
    val comment: PreflightComment,
    val line: Int,
    private val manager: CommentInlayManager
) : EditorCustomElementRenderer {

    var expanded: Boolean = !comment.resolved
    private var replyArea: Rectangle? = null
    private var editCommentArea: Rectangle? = null
    private var resolveArea: Rectangle? = null
    private var deleteArea: Rectangle? = null
    private val replyAreas = mutableListOf<Rectangle>()

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editorEx = inlay.editor as? EditorEx
            ?: return inlay.editor.scrollingModel.visibleArea.width
        val scrollbar = editorEx.scrollPane.verticalScrollBar
        val scrollbarWidth = if (scrollbar.isVisible) scrollbar.width else 0
        return editorEx.scrollPane.viewport.width - scrollbarWidth
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        if (!expanded) return COLLAPSED_HEIGHT
        val editor = inlay.editor
        val font = UIUtil.getLabelFont()
        val fm = editor.contentComponent.getFontMetrics(font)
        val maxWidth = maxOf(editor.scrollingModel.visibleArea.width - 2 * PADDING, 100)
        val commentLines = wrapLines(comment.comment, maxWidth) { fm.stringWidth(it) }.size
        val replyLines = comment.replies.sumOf { wrapLines("  ↳ ${it.text}", maxWidth - 12) { fm.stringWidth(it) }.size + 1 }
        return PADDING * 2 + (commentLines + replyLines + 1) * ROW_HEIGHT + ROW_HEIGHT
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = UIUtil.getPanelBackground()
        g2.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
        g2.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
        g2.fillRect(targetRegion.x, targetRegion.y, ACCENT_BAR_WIDTH, targetRegion.height)
        val separator = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
        g2.color = separator
        g2.drawLine(targetRegion.x, targetRegion.y, targetRegion.x + targetRegion.width, targetRegion.y)
        g2.drawLine(targetRegion.x, targetRegion.y + targetRegion.height - 1, targetRegion.x + targetRegion.width, targetRegion.y + targetRegion.height - 1)
        g2.font = UIUtil.getLabelFont()
        if (!expanded) paintCollapsed(g2, targetRegion)
        else paintExpanded(g2, targetRegion)
    }

    private fun paintCollapsed(g: Graphics2D, r: Rectangle) {
        g.color = UIUtil.getContextHelpForeground()
        val firstLine = comment.comment.lines().first().take(60)
        val suffix = if (comment.replies.isNotEmpty())
            " (${comment.replies.size} ${if (comment.replies.size == 1) "reply" else "replies"})"
        else ""
        g.drawString("✓ $firstLine$suffix", r.x + PADDING, r.y + COLLAPSED_HEIGHT / 2 + g.fontMetrics.ascent / 2)
    }

    private fun paintExpanded(g: Graphics2D, r: Rectangle) {
        val fm = g.fontMetrics
        val maxWidth = maxOf(r.width - 2 * PADDING, 100)
        var yOff = PADDING

        val authorLabel = if (comment.author == "agent") "Agent" else "You"
        val smallFont = g.font.deriveFont(g.font.size - 1f)
        g.font = smallFont
        g.color = UIUtil.getContextHelpForeground()
        g.drawString(authorLabel, r.x + PADDING, r.y + yOff + g.fontMetrics.ascent)
        yOff += ROW_HEIGHT
        g.font = UIUtil.getLabelFont()

        g.color = UIUtil.getLabelForeground()
        for (wrappedLine in wrapLines(comment.comment, maxWidth) { fm.stringWidth(it) }) {
            g.drawString(wrappedLine, r.x + PADDING, r.y + yOff + fm.ascent)
            yOff += ROW_HEIGHT
        }

        replyAreas.clear()
        for (reply in comment.replies) {
            val replyStartY = yOff
            val replyAuthorLabel = if (reply.author == "agent") "Agent" else "You"
            g.font = g.font.deriveFont(g.font.size - 1f)
            g.color = UIUtil.getContextHelpForeground()
            g.drawString(replyAuthorLabel, r.x + PADDING + 12, r.y + yOff + g.fontMetrics.ascent)
            yOff += ROW_HEIGHT
            g.font = UIUtil.getLabelFont()
            g.color = UIUtil.getContextHelpForeground()
            val wrappedLines = wrapLines("  ↳ ${reply.text}", maxWidth - 12) { fm.stringWidth(it) }
            for (wrappedLine in wrappedLines) {
                g.drawString(wrappedLine, r.x + PADDING + 12, r.y + yOff + fm.ascent)
                yOff += ROW_HEIGHT
            }
            replyAreas.add(Rectangle(PADDING + 12, replyStartY, maxWidth - 12, yOff - replyStartY))
        }

        val resolveText = if (comment.resolved) "Reopen" else "Resolve"
        val resolveTextWidth = fm.stringWidth(resolveText)
        val deleteTextWidth = fm.stringWidth("Delete")
        val editTextWidth = fm.stringWidth("Edit")

        val replyTextWidth = fm.stringWidth("Reply")
        resolveArea = Rectangle(r.width - resolveTextWidth - PADDING * 2, yOff + 2, resolveTextWidth + PADDING, ROW_HEIGHT - 4)
        deleteArea = Rectangle(resolveArea!!.x - deleteTextWidth - PADDING * 2, yOff + 2, deleteTextWidth + PADDING, ROW_HEIGHT - 4)
        editCommentArea = Rectangle(deleteArea!!.x - editTextWidth - PADDING * 2, yOff + 2, editTextWidth + PADDING, ROW_HEIGHT - 4)
        replyArea = Rectangle(PADDING, yOff + 2, replyTextWidth + PADDING, ROW_HEIGHT - 4)

        g.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
        g.drawString("Reply", r.x + replyArea!!.x + 2, r.y + replyArea!!.y + fm.ascent)

        g.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
        g.drawString("Edit", r.x + editCommentArea!!.x + 2, r.y + editCommentArea!!.y + fm.ascent)

        g.color = JBColor.RED
        g.drawString("Delete", r.x + deleteArea!!.x + 2, r.y + deleteArea!!.y + fm.ascent)

        g.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
        g.drawString(resolveText, r.x + resolveArea!!.x + 2, r.y + resolveArea!!.y + fm.ascent)
    }

    fun handleClick(point: Point, inlay: Inlay<*>) {
        if (!expanded) {
            expanded = true
            inlay.update()
            return
        }
        val bounds = inlay.bounds ?: return
        val rel = Point(point.x - bounds.x, point.y - bounds.y)
        replyArea?.let {
            if (it.contains(rel)) {
                showReplyPopup(inlay.editor as? EditorEx ?: return@let, point)
                return
            }
        }
        editCommentArea?.let {
            if (it.contains(rel)) {
                showEditCommentPopup(inlay.editor as? EditorEx ?: return@let, point)
                return
            }
        }
        replyAreas.forEachIndexed { index, area ->
            if (area.contains(rel)) {
                showEditReplyPopup(inlay.editor as? EditorEx ?: return, point, index)
                return
            }
        }
        deleteArea?.let {
            if (it.contains(rel)) {
                manager.onDelete(line)
                return
            }
        }
        resolveArea?.let {
            if (it.contains(rel)) {
                if (comment.resolved) manager.onReopen(line) else manager.onResolve(line)
            }
        }
    }

    private fun showReplyPopup(editor: EditorEx, point: Point) {
        CommentInputPopup.showAtPoint(
            anchorComponent = editor.contentComponent,
            anchorPoint = point,
            title = "REPLY",
            saveLabel = "Reply ↵"
        ) { text -> manager.onReply(line, text) }
    }

    private fun showEditCommentPopup(editor: EditorEx, point: Point) {
        CommentInputPopup.show(editor, line, initialText = comment.comment) { newText ->
            manager.onEditComment(line, newText)
        }
    }

    private fun showEditReplyPopup(editor: EditorEx, point: Point, replyIndex: Int) {
        val existing = comment.replies.getOrNull(replyIndex)?.text ?: return
        CommentInputPopup.showAtPoint(
            anchorComponent = editor.contentComponent,
            anchorPoint = point,
            title = "EDIT REPLY",
            saveLabel = "Save ↵",
            initialText = existing
        ) { text -> manager.onEditReply(line, replyIndex, text) }
    }

    companion object {
        private const val COLLAPSED_HEIGHT = 24
        private const val ROW_HEIGHT = 22
        private const val PADDING = 8
        private const val ACCENT_BAR_WIDTH = 3

        internal fun wrapLines(text: String, maxWidth: Int, measure: (String) -> Int): List<String> {
            if (maxWidth <= 0 || measure(text) <= maxWidth) return listOf(text)
            val words = text.split(" ")
            val lines = mutableListOf<String>()
            var current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (measure(candidate) <= maxWidth) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) lines.add(current.toString())
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) lines.add(current.toString())
            return lines.ifEmpty { listOf(text) }
        }
    }
}
