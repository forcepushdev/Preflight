package com.forcepushdev.preflight.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange

class CommentAnchor(private val document: Document, startLine: Int, endLine: Int) {

    private var hadContent = false
    private lateinit var marker: RangeMarker

    // The last range the marker covered, to fall back on should the marker ever be invalidated.
    private var lastEndLine = endLine

    init {
        anchor(startLine, endLine)
    }

    // surviveOnExternalChange: when a file is rewritten on disk (an agent editing it while
    // IntelliJ is open) the document is reloaded as a whole; a plain marker would be invalidated,
    // a persistent one is re-mapped through a diff of old and new text.
    private fun anchor(startLine: Int, endLine: Int) {
        hadContent = document.getLineEndOffset(endLine) > document.getLineStartOffset(startLine)
        marker = document.createRangeMarker(
            document.getLineStartOffset(startLine),
            document.getLineEndOffset(endLine),
            true
        )
    }

    /**
     * The live marker. When the anchored text was deleted or replaced as a whole (typically an agent
     * rewriting exactly the commented lines) the marker collapses; the comment then moves to the line
     * it collapsed on rather than getting lost.
     */
    private fun liveMarker(): RangeMarker = synchronized(this) {
        val current = marker
        val collapsed = current.isValid && hadContent && current.startOffset == current.endOffset
        if (current.isValid && !collapsed) {
            lastEndLine = document.getLineNumber(current.endOffset)
            return current
        }
        val line = if (current.isValid) {
            document.getLineNumber(current.startOffset)
        } else {
            lastEndLine.coerceIn(0, maxOf(0, document.lineCount - 1))
        }
        current.dispose()
        anchor(line, line)
        lastEndLine = line
        marker
    }

    // RangeMarker.getDocument()/getLineNumber() resolve through FileDocumentManager, which
    // asserts read access even when already on EDT — callers may run on EDT (button click
    // handlers) or a pooled thread (CommentAnchorRegistry's debounced flush), neither of which
    // holds an implicit read lock, so this must always acquire one explicitly.

    // currentLine tracks the END of the anchored range (comment.line), not the start: the
    // inlay/gutter icon renders at the end line.
    val currentLine: Int
        get() = readAccess { document.getLineNumber(liveMarker().endOffset) }

    val currentStartLine: Int
        get() = readAccess { document.getLineNumber(liveMarker().startOffset) }

    // Full text of the anchored lines, persisted as `anchorText` so the comment can be re-found by
    // content if line numbers go stale (see AnchorRelocator).
    val currentText: String
        get() = readAccess {
            val live = liveMarker()
            document.lineRangeText(document.getLineNumber(live.startOffset), document.getLineNumber(live.endOffset))
        }
}

/** Text from the start of [startLine] to the end of [endLine], with both lines clamped into the document. */
fun Document.lineRangeText(startLine: Int, endLine: Int): String {
    val last = (lineCount - 1).coerceAtLeast(0)
    val from = startLine.coerceIn(0, last)
    val to = endLine.coerceIn(from, last)
    return getText(TextRange(getLineStartOffset(from), getLineEndOffset(to)))
}

/**
 * Runs [block] under a read action. Not `ReadAction.compute`/`runReadAction`: both are deprecated
 * from 2026.1, and their replacement `computeBlocking` does not exist in our since-build (252).
 */
internal fun <T> readAccess(block: () -> T): T =
    ApplicationManager.getApplication().runReadAction(Computable { block() })
