package com.forcepushdev.preflight.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks a Comment Anchor per open (document, comment) pair and, after a quiet period following
 * document edits, writes the live line position back to the CommentStore (debounced, see ADR 0002)
 * and flags anchors whose content was deleted as orphaned.
 */
@Service(Service.Level.PROJECT)
class CommentAnchorRegistry @JvmOverloads constructor(
    private val project: Project,
    private val debounceMs: Int = 1500
) : Disposable {

    private data class Key(val document: Document, val commentId: String)

    // What the CommentStore held for this comment when the anchor was created / last flushed. Lets
    // register() tell "store is merely stale" (keep the live anchor) from "someone edited line or
    // startLine in comments.json" (an agent moved the comment: re-anchor).
    private class Entry(
        val anchor: CommentAnchor,
        var storedStart: Int,
        var storedEnd: Int,
        var storedText: String?
    )

    // Accessed from the EDT (register) and the pooled flush thread.
    private val anchors = ConcurrentHashMap<Key, Entry>()
    private val listenedDocuments = mutableSetOf<Document>()
    private val orphanedIds = mutableSetOf<String>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    fun register(document: Document, comment: PreflightComment) {
        val lastLine = maxOf(0, document.lineCount - 1)
        val storedStart = (comment.startLine ?: comment.line).coerceIn(0, lastLine)
        val storedEnd = comment.line.coerceIn(0, lastLine)
        val key = Key(document, comment.id)

        val existing = anchors[key]
        if (existing != null && existing.storedStart == storedStart && existing.storedEnd == storedEnd) return

        var startLine = storedStart
        var endLine = storedEnd
        comment.anchorText?.let { text ->
            AnchorRelocator.relocate(documentLines(document), storedStart, text)?.let { found ->
                startLine = found.first.coerceIn(0, lastLine)
                endLine = found.last.coerceIn(startLine, lastLine)
            }
        }
        anchors[key] = Entry(CommentAnchor(document, startLine, endLine), storedStart, storedEnd, comment.anchorText)
        if (listenedDocuments.add(document)) {
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = scheduleFlush()
            }, this)
        }
        // Relocated, or a legacy comment without anchorText: persist the corrected/backfilled state.
        if (startLine != storedStart || endLine != storedEnd || comment.anchorText == null) scheduleFlush()
    }

    private fun documentLines(document: Document): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        (0 until document.lineCount).map {
            document.getText(TextRange(document.getLineStartOffset(it), document.getLineEndOffset(it)))
        }
    }

    fun unregister(document: Document, commentId: String) {
        anchors.remove(Key(document, commentId))
    }

    fun currentLine(document: Document, commentId: String): Int? = anchors[Key(document, commentId)]?.anchor?.currentLine

    /** Live start..end line of the comment; unlike the stored values it is correct between an edit and the next flush. */
    fun currentRange(document: Document, commentId: String): IntRange? {
        val anchor = anchors[Key(document, commentId)]?.anchor ?: return null
        val start = anchor.currentStartLine ?: return null
        val end = anchor.currentLine ?: return null
        return start..end
    }

    fun isOrphaned(commentId: String): Boolean = commentId in orphanedIds

    fun orphanedIds(): Set<String> = orphanedIds.toSet()

    private fun scheduleFlush() {
        alarm.cancelAllRequests()
        alarm.addRequest({ flush() }, debounceMs)
    }

    private fun flush() {
        val store = project.service<CommentStore>()
        orphanedIds.clear()
        for ((key, entry) in anchors) {
            val anchor = entry.anchor
            if (anchor.isOrphaned) {
                orphanedIds += key.commentId
                continue
            }
            val start = anchor.currentStartLine ?: continue
            val end = anchor.currentLine ?: continue
            val text = anchor.currentText
            if (start == entry.storedStart && end == entry.storedEnd && text == entry.storedText) continue
            store.updateAnchor(key.commentId, start, end, text)
            entry.storedStart = start
            entry.storedEnd = end
            entry.storedText = text
        }
    }

    override fun dispose() {
        anchors.clear()
        listenedDocuments.clear()
    }
}
