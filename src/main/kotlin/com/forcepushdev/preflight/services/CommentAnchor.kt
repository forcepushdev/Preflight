package com.forcepushdev.preflight.services

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker

class CommentAnchor(document: Document, startLine: Int, endLine: Int) {

    private val hadContent =
        document.getLineEndOffset(endLine) > document.getLineStartOffset(startLine)

    private val marker: RangeMarker = document.createRangeMarker(
        document.getLineStartOffset(startLine),
        document.getLineEndOffset(endLine)
    )

    // RangeMarker.getDocument()/getLineNumber() resolve through FileDocumentManager, which
    // asserts read access even when already on EDT — callers may run on EDT (button click
    // handlers) or a pooled thread (CommentAnchorRegistry's debounced flush), neither of which
    // holds an implicit read lock, so this must always acquire one explicitly.
    val isOrphaned: Boolean
        get() = ReadAction.compute<Boolean, RuntimeException> {
            !marker.isValid || (hadContent && marker.startOffset == marker.endOffset)
        }

    val currentLine: Int?
        get() = ReadAction.compute<Int?, RuntimeException> {
            if (isOrphaned) null else marker.document.getLineNumber(marker.startOffset)
        }
}
