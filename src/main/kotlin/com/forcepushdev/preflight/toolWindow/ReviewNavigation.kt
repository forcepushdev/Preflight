package com.forcepushdev.preflight.toolWindow

enum class NavDirection { NEXT, PREVIOUS }

enum class NavKind { COMMENT, CHANGE }

data class CommentPosition(val line: Int, val id: String)

/** Where a review navigation step lands. Lines are 0-based, on the HEAD (right) side of the diff. */
sealed class NavTarget {
    abstract val file: String

    data class Line(override val file: String, val line: Int, val commentId: String? = null) : NavTarget()
    data class FirstChange(override val file: String) : NavTarget()
    data class LastChange(override val file: String) : NavTarget()
}

/**
 * Next/previous Comment or change across the Review Diff. Files are visited in the order the
 * Preflight file tree shows them; stepping past the last file wraps around to the first.
 */
object ReviewNavigation {

    fun stepInFile(lines: List<Int>, currentLine: Int, direction: NavDirection): Int? = when (direction) {
        NavDirection.NEXT -> lines.filter { it > currentLine }.minOrNull()
        NavDirection.PREVIOUS -> lines.filter { it < currentLine }.maxOrNull()
    }

    fun adjacentFile(files: List<String>, currentFile: String?, direction: NavDirection): String? {
        if (files.isEmpty()) return null
        val index = files.indexOf(currentFile)
        if (index < 0) return if (direction == NavDirection.NEXT) files.first() else files.last()
        val step = if (direction == NavDirection.NEXT) 1 else -1
        return files[(index + step + files.size) % files.size]
    }

    /**
     * [currentCommentId] is the comment the caret was last moved to. While the caret still sits on
     * its line, stepping goes by comment order rather than by line, so comments sharing a line are
     * each visited instead of being skipped or revisited.
     */
    fun nextComment(
        files: List<String>,
        comments: (String) -> List<CommentPosition>,
        currentFile: String?,
        currentLine: Int,
        direction: NavDirection,
        currentCommentId: String? = null
    ): NavTarget.Line? {
        if (currentFile != null && currentFile in files) {
            val ordered = comments(currentFile).sortedWith(compareBy({ it.line }, { it.id }))
            val index = ordered.indexOfFirst { it.id == currentCommentId && it.line == currentLine }
            val next = if (index >= 0) {
                ordered.getOrNull(if (direction == NavDirection.NEXT) index + 1 else index - 1)
            } else when (direction) {
                NavDirection.NEXT -> ordered.firstOrNull { it.line > currentLine }
                NavDirection.PREVIOUS -> ordered.lastOrNull { it.line < currentLine }
            }
            next?.let { return NavTarget.Line(currentFile, it.line, it.id) }
        }
        // The current file comes last, so a single commented file wraps around onto itself.
        for (file in filesAfter(files, currentFile, direction)) {
            val ordered = comments(file).sortedWith(compareBy({ it.line }, { it.id }))
            val pick = (if (direction == NavDirection.NEXT) ordered.firstOrNull() else ordered.lastOrNull()) ?: continue
            return NavTarget.Line(file, pick.line, pick.id)
        }
        return null
    }

    fun nextChange(
        files: List<String>,
        changeLinesInCurrentFile: List<Int>,
        currentFile: String?,
        currentLine: Int,
        direction: NavDirection
    ): NavTarget? {
        if (currentFile != null && currentFile in files) {
            stepInFile(changeLinesInCurrentFile, currentLine, direction)?.let { return NavTarget.Line(currentFile, it) }
        }
        val file = adjacentFile(files, currentFile, direction) ?: return null
        if (file == currentFile) {
            val lines = changeLinesInCurrentFile.ifEmpty { return null }
            return NavTarget.Line(file, if (direction == NavDirection.NEXT) lines.min() else lines.max())
        }
        return if (direction == NavDirection.NEXT) NavTarget.FirstChange(file) else NavTarget.LastChange(file)
    }

    private fun filesAfter(files: List<String>, currentFile: String?, direction: NavDirection): List<String> {
        val ordered = if (direction == NavDirection.NEXT) files else files.reversed()
        val index = ordered.indexOf(currentFile)
        return if (index < 0) ordered else ordered.drop(index + 1) + ordered.take(index + 1)
    }
}
