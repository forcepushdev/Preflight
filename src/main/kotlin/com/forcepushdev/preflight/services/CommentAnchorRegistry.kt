package com.forcepushdev.preflight.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

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

    private val anchors = mutableMapOf<Key, CommentAnchor>()
    private val listenedDocuments = mutableSetOf<Document>()
    private val orphanedIds = mutableSetOf<String>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    fun register(document: Document, comment: PreflightComment) {
        val lastLine = maxOf(0, document.lineCount - 1)
        val startLine = (comment.startLine ?: comment.line).coerceIn(0, lastLine)
        val endLine = comment.line.coerceIn(0, lastLine)
        anchors[Key(document, comment.id)] = CommentAnchor(document, startLine, endLine)
        if (listenedDocuments.add(document)) {
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = scheduleFlush()
            }, this)
        }
    }

    fun unregister(document: Document, commentId: String) {
        anchors.remove(Key(document, commentId))
    }

    fun currentLine(document: Document, commentId: String): Int? = anchors[Key(document, commentId)]?.currentLine

    fun isOrphaned(commentId: String): Boolean = commentId in orphanedIds

    fun orphanedIds(): Set<String> = orphanedIds.toSet()

    private fun scheduleFlush() {
        alarm.cancelAllRequests()
        alarm.addRequest({ flush() }, debounceMs)
    }

    private fun flush() {
        val store = project.service<CommentStore>()
        orphanedIds.clear()
        for ((key, anchor) in anchors) {
            if (anchor.isOrphaned) {
                orphanedIds += key.commentId
            } else {
                anchor.currentLine?.let { store.updateLine(key.commentId, it) }
            }
        }
    }

    override fun dispose() {
        anchors.clear()
        listenedDocuments.clear()
    }
}
