package com.forcepushdev.preflight.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange

class CommentAnchor(document: Document, startLine: Int, endLine: Int) {

    private val hadContent =
        document.getLineEndOffset(endLine) > document.getLineStartOffset(startLine)

    // surviveOnExternalChange: when a file is rewritten on disk (an agent editing it while
    // IntelliJ is open) the document is reloaded as a whole; a plain marker would be invalidated,
    // a persistent one is re-mapped through a diff of old and new text.
    private val marker: RangeMarker = document.createRangeMarker(
        document.getLineStartOffset(startLine),
        document.getLineEndOffset(endLine),
        true
    )

    // RangeMarker.getDocument()/getLineNumber() resolve through FileDocumentManager, which
    // asserts read access even when already on EDT — callers may run on EDT (button click
    // handlers) or a pooled thread (CommentAnchorRegistry's debounced flush), neither of which
    // holds an implicit read lock, so this must always acquire one explicitly.
    val isOrphaned: Boolean
        get() = readAccess {
            !marker.isValid || (hadContent && marker.startOffset == marker.endOffset)
        }

    // currentLine tracks the END of the anchored range (comment.line), not the start: the
    // inlay/gutter icon renders at the end line, and CommentInlayManager derives the highlight's
    // start by subtracting the original span from this value (see CommentInlayManager.refresh).
    val currentLine: Int?
        get() = readAccess {
            if (isOrphaned) null else marker.document.getLineNumber(marker.endOffset)
        }

    val currentStartLine: Int?
        get() = readAccess {
            if (isOrphaned) null else marker.document.getLineNumber(marker.startOffset)
        }

    // Full text of the anchored lines, persisted as `anchorText` so the comment can be re-found by
    // content if line numbers go stale (see AnchorRelocator).
    val currentText: String?
        get() = readAccess {
            if (isOrphaned) null
            else marker.document.lineRangeText(
                marker.document.getLineNumber(marker.startOffset),
                marker.document.getLineNumber(marker.endOffset)
            )
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
