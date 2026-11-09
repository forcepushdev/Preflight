package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.PreflightComment
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.geom.RoundRectangle2D

class CommentInlayRenderer(
    val comment: PreflightComment,
    val line: Int,
    private val startLine: Int,
    private val manager: CommentInlayManager,
    private val onCollapse: (String) -> Unit
) : EditorCustomElementRenderer {

    // Hit areas, relative to the inlay's origin.
    private var collapseArea: Rectangle? = null
    private var copyArea: Rectangle? = null
    private var replyArea: Rectangle? = null
    private var editCommentArea: Rectangle? = null
    private var deleteArea: Rectangle? = null
    private var resolveArea: Rectangle? = null
    private val replyEditAreas = mutableListOf<Rectangle>()
    private val replyDeleteAreas = mutableListOf<Rectangle>()
    private val replyCopyAreas = mutableListOf<Rectangle>()

    // Selectable text as last painted, and the selection over it.
    private var textRows: List<TextRow> = emptyList()
    private var selectionAnchor: TextPos? = null
    private var selectionFocus: TextPos? = null

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editorEx = inlay.editor as? EditorEx
            ?: return inlay.editor.scrollingModel.visibleArea.width
        val scrollbar = editorEx.scrollPane.verticalScrollBar
        val scrollbarWidth = if (scrollbar.isVisible) scrollbar.width else 0
        return editorEx.scrollPane.viewport.width - scrollbarWidth
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        layout(null, Rectangle(0, 0, calcWidthInPixels(inlay), 0), layoutMetrics(inlay))

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = inlay.editor.colorsScheme.defaultBackground
            g2.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

            val card = RoundRectangle2D.Float(
                (targetRegion.x + MARGIN).toFloat(), (targetRegion.y + MARGIN).toFloat(),
                (targetRegion.width - 2 * MARGIN - 1).toFloat(), (targetRegion.height - 2 * MARGIN - 1).toFloat(),
                ARC.toFloat(), ARC.toFloat()
            )
            g2.color = UIUtil.getPanelBackground()
            g2.fill(card)
            val clip = g2.clip
            g2.clip(card)
            g2.color = ACCENT
            g2.fillRect(targetRegion.x + MARGIN, targetRegion.y + MARGIN, ACCENT_BAR_WIDTH, targetRegion.height)
            g2.clip = clip
            g2.color = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            g2.draw(card)

            layout(g2, targetRegion, layoutMetrics(inlay))
        } finally {
            g2.dispose()
        }
    }

    // Wrapping must measure exactly the same when sizing and when painting, otherwise paint produces
    // more rows than the inlay reserved and the buttons are drawn below the inlay, over the code.
    private fun layoutMetrics(inlay: Inlay<*>): Metrics {
        val component = inlay.editor.contentComponent
        return Metrics(
            component.getFontMetrics(UIUtil.getLabelFont()),
            component.getFontMetrics(codeFont(inlay.editor)),
            inlay.editor.colorsScheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR) ?: UIUtil.getListSelectionBackground(true)
        )
    }

    private class Metrics(val label: FontMetrics, val code: FontMetrics, val selection: Color)

    private fun codeFont(editor: Editor): Font =
        editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(UIUtil.getLabelFont().size2D - 1f)

    /**
     * Lays out the card, painting it when [g] is given, and returns the height it takes.
     * Sizing and painting share this one pass so they cannot drift apart.
     */
    private fun layout(g: Graphics2D?, r: Rectangle, metrics: Metrics): Int {
        val fm = metrics.label
        val codeFm = metrics.code
        val labelFont = UIUtil.getLabelFont()
        val smallFont = labelFont.deriveFont(labelFont.size - 1f)
        val left = MARGIN + ACCENT_BAR_WIDTH + CARD_PADDING
        val right = r.width - MARGIN - CARD_PADDING
        val textWidth = maxOf(right - left, 100)
        var y = MARGIN + CARD_PADDING
        val rows = mutableListOf<TextRow>()
        val selection = selectionAnchor?.let { a -> selectionFocus?.let { b -> if (a <= b) a to b else b to a } }

        fun text(s: String, x: Int, color: Color, font: Font = labelFont, rowHeight: Int = ROW_HEIGHT, top: Int = y) {
            g ?: return
            g.font = font
            g.color = color
            val m = g.fontMetrics
            g.drawString(s, r.x + x, r.y + top + (rowHeight - m.height) / 2 + m.ascent)
        }

        fun roundedBox(x: Int, top: Int, width: Int, height: Int) {
            g ?: return
            g.color = CODE_BACKGROUND
            g.fill(RoundRectangle2D.Float(
                (r.x + x).toFloat(), (r.y + top).toFloat(), width.toFloat(), height.toFloat(), CODE_ARC.toFloat(), CODE_ARC.toFloat()
            ))
        }

        fun runWidth(run: CommentText.Segment) =
            if (run.isCode) codeFm.stringWidth(run.text) + 2 * CODE_PAD else fm.stringWidth(run.text)

        fun paintSelection(row: TextRow) {
            g ?: return
            val (start, end) = selection?.takeIf { it.first != it.second } ?: return
            val index = rows.size
            if (index < start.row || index > end.row) return
            val from = row.xs[if (index == start.row) start.col else 0]
            val to = if (index == end.row) row.xs[end.col] else row.xs.last() + SELECTION_EOL
            g.color = metrics.selection
            g.fillRect(r.x + from, r.y + row.top, to - from, row.height)
        }

        /**
         * Comment or reply text, with inline code and fenced code blocks drawn as code. Its rows become
         * selectable text in [group].
         */
        fun body(source: String, x: Int, width: Int, group: Int) {
            val color = UIUtil.getLabelForeground()
            for (block in CommentText.parse(source)) when (block) {
                is CommentText.Prose -> {
                    val wrapped = CommentText.wrap(block.segments, width, ::runWidth)
                    wrapped.forEachIndexed { rowIndex, runs ->
                        val xs = mutableListOf(x)
                        var cx = x
                        for (run in runs) {
                            val w = runWidth(run)
                            val inner = if (run.isCode) cx + CODE_PAD else cx
                            val m = if (run.isCode) codeFm else fm
                            for (k in 1..run.text.length) {
                                xs += if (k == run.text.length) cx + w else inner + m.stringWidth(run.text.substring(0, k))
                            }
                            cx += w
                        }
                        val separator = if (rowIndex < wrapped.lastIndex) " " else "\n"
                        val row = TextRow(group, y, ROW_HEIGHT, runs.joinToString("") { it.text }, xs.toIntArray(), x + width, separator)
                        cx = x
                        for (run in runs) {
                            val w = runWidth(run)
                            if (run.isCode) roundedBox(cx, y + (ROW_HEIGHT - CODE_CHIP_HEIGHT) / 2, w, CODE_CHIP_HEIGHT)
                            cx += w
                        }
                        paintSelection(row)
                        cx = x
                        for (run in runs) {
                            if (run.isCode) text(run.text, cx + CODE_PAD, color, codeFm.font) else text(run.text, cx, color)
                            cx += runWidth(run)
                        }
                        rows += row
                        y += ROW_HEIGHT
                    }
                }
                is CommentText.Code -> {
                    val codeRows = block.lines.flatMap { line ->
                        val pieces = CommentText.wrapCode(line, width - 2 * CODE_BLOCK_PAD) { codeFm.stringWidth(it) }
                        pieces.mapIndexed { i, piece -> piece to if (i < pieces.lastIndex) "" else "\n" }
                    }
                    val height = codeRows.size * CODE_ROW_HEIGHT + 2 * CODE_BLOCK_VPAD
                    y += CODE_BLOCK_GAP
                    roundedBox(x, y, width, height)
                    val textX = x + CODE_BLOCK_PAD
                    codeRows.forEachIndexed { i, (piece, separator) ->
                        val top = y + CODE_BLOCK_VPAD + i * CODE_ROW_HEIGHT
                        val xs = IntArray(piece.length + 1) { k -> textX + codeFm.stringWidth(piece.substring(0, k)) }
                        val row = TextRow(group, top, CODE_ROW_HEIGHT, piece, xs, x + width, separator)
                        paintSelection(row)
                        text(piece, textX, color, codeFm.font, CODE_ROW_HEIGHT, top)
                        rows += row
                    }
                    y += height + CODE_BLOCK_GAP
                }
            }
            rows.lastOrNull()?.takeIf { it.group == group }?.let { rows[rows.lastIndex] = it.copy(separator = "\n\n") }
        }

        /** A text link whose right edge is at [rightEdge]; returns its hit area. */
        fun link(label: String, rightEdge: Int, color: Color): Rectangle {
            val w = fm.stringWidth(label)
            text(label, rightEdge - w, color)
            return Rectangle(rightEdge - w - LINK_PAD, y, w + 2 * LINK_PAD, ROW_HEIGHT)
        }

        fun buttonWidth(label: String) = fm.stringWidth(label) + 2 * BUTTON_PAD

        fun button(label: String, x: Int, style: ButtonStyle): Rectangle {
            val area = Rectangle(x, y, buttonWidth(label), BUTTON_HEIGHT)
            if (g != null) {
                val shape = RoundRectangle2D.Float(
                    (r.x + area.x).toFloat(), (r.y + area.y).toFloat(),
                    area.width.toFloat(), area.height.toFloat(), ARC.toFloat(), ARC.toFloat()
                )
                when (style) {
                    ButtonStyle.PRIMARY -> { g.color = ACCENT; g.fill(shape) }
                    ButtonStyle.DANGER -> { g.color = DANGER; g.draw(shape) }
                    ButtonStyle.DEFAULT -> { g.color = JBColor.border(); g.draw(shape) }
                }
                val color = when (style) {
                    ButtonStyle.PRIMARY -> Color.WHITE
                    ButtonStyle.DANGER -> DANGER
                    ButtonStyle.DEFAULT -> UIUtil.getLabelForeground()
                }
                text(label, x + BUTTON_PAD, color, rowHeight = BUTTON_HEIGHT)
            }
            return area
        }

        // Header: who and where, with Copy and collapse on the right.
        val collapseWidth = fm.stringWidth(COLLAPSE_ICON)
        collapseArea = Rectangle(right - collapseWidth - LINK_PAD, y, collapseWidth + 2 * LINK_PAD, ROW_HEIGHT)
        text(COLLAPSE_ICON, right - collapseWidth, MUTED)
        copyArea = link("Copy", collapseArea!!.x - LINK_GAP, MUTED)
        text(headerLabel(comment.author, startLine, line, comment.resolved), left, MUTED, smallFont)
        y += ROW_HEIGHT

        body(comment.comment, left, textWidth, group = 0)

        replyEditAreas.clear()
        replyDeleteAreas.clear()
        replyCopyAreas.clear()
        comment.replies.forEachIndexed { replyIndex, reply ->
            y += REPLY_GAP
            val top = y
            val isAgent = reply.author.equals("agent", ignoreCase = true)
            text(if (isAgent) "✦ Agent" else "You", left + REPLY_INDENT, if (isAgent) AGENT else MUTED, smallFont)
            y += ROW_HEIGHT
            body(reply.text, left + REPLY_INDENT, textWidth - REPLY_INDENT, group = replyIndex + 1)
            val copy = link("Copy", right, MUTED)
            val delete = link("Delete", copy.x - LINK_GAP, DANGER)
            val edit = link("Edit", delete.x - LINK_GAP, MUTED)
            replyCopyAreas += copy
            replyDeleteAreas += delete
            replyEditAreas += edit
            y += ROW_HEIGHT
            g?.let {
                it.color = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
                it.fillRect(r.x + left, r.y + top, 2, y - top)
            }
        }

        y += SECTION_GAP
        g?.let {
            it.color = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            it.drawLine(r.x + left, r.y + y, r.x + right, r.y + y)
        }
        y += SECTION_GAP

        replyArea = button("Reply", left, ButtonStyle.DEFAULT)
        val resolveLabel = if (comment.resolved) "Reopen" else "Resolve"
        val resolveX = right - buttonWidth(resolveLabel)
        resolveArea = button(resolveLabel, resolveX, if (comment.resolved) ButtonStyle.DEFAULT else ButtonStyle.PRIMARY)
        val deleteX = resolveX - BUTTON_GAP - buttonWidth("Delete")
        deleteArea = button("Delete", deleteX, ButtonStyle.DANGER)
        editCommentArea = button("Edit", deleteX - BUTTON_GAP - buttonWidth("Edit"), ButtonStyle.DEFAULT)
        y += BUTTON_HEIGHT

        if (g != null) textRows = rows
        return y + CARD_PADDING + MARGIN
    }

    private enum class ButtonStyle { DEFAULT, DANGER, PRIMARY }

    fun handleClick(point: Point, inlay: Inlay<*>) {
        val bounds = inlay.bounds ?: return
        val rel = Point(point.x - bounds.x, point.y - bounds.y)
        val editor = inlay.editor as? EditorEx
        fun hit(area: Rectangle?) = area?.contains(rel) == true

        when {
            hit(collapseArea) -> onCollapse(comment.id)
            hit(copyArea) -> copyToClipboard(comment.comment)
            hit(replyArea) -> editor?.let { showReplyPopup(it, point) }
            hit(editCommentArea) -> editor?.let { showEditCommentPopup(it, point) }
            hit(deleteArea) -> manager.onDelete(comment.id)
            hit(resolveArea) -> if (comment.resolved) manager.onReopen(comment.id) else manager.onResolve(comment.id)
            else -> {
                replyEditAreas.indexOfFirst { it.contains(rel) }.takeIf { it >= 0 }
                    ?.let { index -> editor?.let { showEditReplyPopup(it, point, index) }; return }
                replyDeleteAreas.indexOfFirst { it.contains(rel) }.takeIf { it >= 0 }
                    ?.let { index -> manager.onDeleteReply(comment.id, index); return }
                replyCopyAreas.indexOfFirst { it.contains(rel) }.takeIf { it >= 0 }
                    ?.let { index -> comment.replies.getOrNull(index)?.let { copyToClipboard(it.text) } }
            }
        }
    }

    /** Starts a selection at [rel] (inlay coordinates); returns false when [rel] is not on comment text. */
    internal fun startSelection(rel: Point, clickCount: Int): Boolean {
        val pos = TextSelection.hit(textRows, rel.x, rel.y) ?: return false
        val (anchor, focus) = when {
            clickCount >= 3 -> TextSelection.group(textRows, pos)
            clickCount == 2 -> TextSelection.word(textRows, pos)
            else -> pos to pos
        }
        selectionAnchor = anchor
        selectionFocus = focus
        return true
    }

    internal fun extendSelection(rel: Point) {
        if (selectionAnchor == null) return
        selectionFocus = TextSelection.nearest(textRows, rel.x, rel.y) ?: return
    }

    /** Returns whether there was a selection to clear. */
    internal fun clearSelection(): Boolean {
        val had = selectedText() != null
        selectionAnchor = null
        selectionFocus = null
        return had
    }

    internal fun selectedText(): String? {
        val anchor = selectionAnchor ?: return null
        val focus = selectionFocus ?: return null
        return TextSelection.text(textRows, anchor, focus).takeIf { it.isNotEmpty() }
    }

    private fun copyToClipboard(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun showReplyPopup(editor: EditorEx, point: Point) {
        CommentInputPopup.showAtPoint(
            anchorComponent = editor.contentComponent,
            anchorPoint = point,
            title = "REPLY",
            saveLabel = "Reply ↵"
        ) { text -> manager.onReply(comment.id, text) }
    }

    private fun showEditCommentPopup(editor: EditorEx, point: Point) {
        CommentInputPopup.show(editor, line, initialText = comment.comment) { newText ->
            manager.onEditComment(comment.id, newText)
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
        ) { text -> manager.onEditReply(comment.id, replyIndex, text) }
    }

    companion object {
        private const val ROW_HEIGHT = 22
        private const val MARGIN = 8
        private const val CARD_PADDING = 12
        private const val ACCENT_BAR_WIDTH = 3
        private const val ARC = 10
        private const val REPLY_INDENT = 14
        private const val REPLY_GAP = 6
        private const val SECTION_GAP = 8
        private const val LINK_PAD = 4
        private const val LINK_GAP = 14
        private const val BUTTON_HEIGHT = 28
        private const val BUTTON_PAD = 12
        private const val BUTTON_GAP = 8
        private const val COLLAPSE_ICON = "▼"
        private const val CODE_PAD = 4
        private const val CODE_ARC = 6
        private const val CODE_CHIP_HEIGHT = 18
        private const val CODE_ROW_HEIGHT = 18
        private const val CODE_BLOCK_PAD = 10
        private const val CODE_BLOCK_VPAD = 6
        private const val CODE_BLOCK_GAP = 4
        private const val SELECTION_EOL = 4

        private val ACCENT: Color get() = JBUI.CurrentTheme.Link.Foreground.ENABLED
        private val MUTED: Color get() = UIUtil.getContextHelpForeground()
        private val DANGER = JBColor(Color(0xDB, 0x3B, 0x4B), Color(0xF2, 0x6C, 0x78))
        private val AGENT = JBColor(Color(0x20, 0x8A, 0x3C), Color(0x5F, 0xB8, 0x65))
        private val CODE_BACKGROUND = JBColor(Color(0, 0, 0, 0x14), Color(0xFF, 0xFF, 0xFF, 0x14))

        internal fun headerLabel(author: String?, startLine: Int, endLine: Int, resolved: Boolean): String {
            val who = if (author.equals("agent", ignoreCase = true)) "Agent" else "You"
            val lines = if (startLine == endLine) "Line ${startLine + 1}" else "Lines ${startLine + 1}–${endLine + 1}"
            return listOfNotNull(who, lines, "Resolved".takeIf { resolved }).joinToString(" · ")
        }
    }
}
