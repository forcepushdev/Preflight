package com.forcepushdev.preflight.services

/**
 * Re-finds a comment's lines in a file by content. Pure and IntelliJ-free on purpose: it is the
 * same lookup an agent without IntelliJ performs with `anchorText`, and the safety net for edits
 * that happened while no live Comment Anchor was tracking the document.
 */
object AnchorRelocator {

    /**
     * Returns the line range where [anchorText] sits in [documentLines], or null if it is gone
     * (or [anchorText] is blank and therefore not a usable needle). A match at [startLine] wins;
     * otherwise the occurrence closest to it. Whitespace at line edges is ignored so reformatting
     * doesn't break the match.
     */
    fun relocate(documentLines: List<String>, startLine: Int, anchorText: String): IntRange? {
        val needle = anchorText.lines().map { it.trim() }
        if (needle.all { it.isEmpty() }) return null
        val lastStart = documentLines.size - needle.size
        val matches = (0..lastStart).filter { start ->
            needle.indices.all { documentLines[start + it].trim() == needle[it] }
        }
        val best = matches.minByOrNull { kotlin.math.abs(it - startLine) } ?: return null
        return best..(best + needle.size - 1)
    }
}
