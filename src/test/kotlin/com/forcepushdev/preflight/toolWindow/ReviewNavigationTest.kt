package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.toolWindow.NavDirection.NEXT
import com.forcepushdev.preflight.toolWindow.NavDirection.PREVIOUS
import junit.framework.TestCase

class ReviewNavigationTest : TestCase() {

    private val files = listOf("a.kt", "b.kt", "c.kt")

    private fun positions(map: Map<String, List<Int>>): (String) -> List<CommentPosition> =
        { file -> map[file].orEmpty().map { CommentPosition(it, "$file:$it") } }

    private fun line(file: String, line: Int) = NavTarget.Line(file, line, "$file:$line")

    fun testNextComment_inSameFile_jumpsToFollowingLine() {
        val comments = mapOf("a.kt" to listOf(3, 10, 20))

        val target = ReviewNavigation.nextComment(files, positions(comments), "a.kt", 10, NEXT)

        assertEquals(line("a.kt", 20), target)
    }

    fun testPreviousComment_inSameFile_jumpsToPrecedingLine() {
        val comments = mapOf("a.kt" to listOf(3, 10, 20))

        val target = ReviewNavigation.nextComment(files, positions(comments), "a.kt", 10, PREVIOUS)

        assertEquals(line("a.kt", 3), target)
    }

    fun testNextComment_pastLastInFile_skipsFilesWithoutComments() {
        val comments = mapOf("a.kt" to listOf(3), "c.kt" to listOf(7, 2))

        val target = ReviewNavigation.nextComment(files, positions(comments), "a.kt", 3, NEXT)

        assertEquals(line("c.kt", 2), target)
    }

    fun testPreviousComment_beforeFirstInFile_landsOnLastCommentOfPreviousFile() {
        val comments = mapOf("a.kt" to listOf(3, 9), "b.kt" to listOf(5))

        val target = ReviewNavigation.nextComment(files, positions(comments), "b.kt", 5, PREVIOUS)

        assertEquals(line("a.kt", 9), target)
    }

    fun testNextComment_atLastComment_wrapsToFirstFile() {
        val comments = mapOf("a.kt" to listOf(4), "c.kt" to listOf(1))

        val target = ReviewNavigation.nextComment(files, positions(comments), "c.kt", 1, NEXT)

        assertEquals(line("a.kt", 4), target)
    }

    fun testNextComment_onlyCommentedFileIsCurrent_wrapsOntoItself() {
        val comments = mapOf("b.kt" to listOf(4, 8))

        val target = ReviewNavigation.nextComment(files, positions(comments), "b.kt", 8, NEXT)

        assertEquals(line("b.kt", 4), target)
    }

    fun testNextComment_noFileOpen_startsAtFirstComment() {
        val comments = mapOf("b.kt" to listOf(4))

        val target = ReviewNavigation.nextComment(files, positions(comments), null, -1, NEXT)

        assertEquals(line("b.kt", 4), target)
    }

    fun testNextComment_noComments_returnsNull() {
        assertNull(ReviewNavigation.nextComment(files, { emptyList() }, "a.kt", 0, NEXT))
    }

    fun testNextComment_sameLine_visitsEachCommentInTurn() {
        val onSameLine = { file: String ->
            if (file == "a.kt") listOf(CommentPosition(5, "x"), CommentPosition(5, "y"), CommentPosition(9, "z")) else emptyList()
        }

        val first = ReviewNavigation.nextComment(files, onSameLine, "a.kt", 0, NEXT)
        val second = ReviewNavigation.nextComment(files, onSameLine, "a.kt", 5, NEXT, first?.commentId)
        val third = ReviewNavigation.nextComment(files, onSameLine, "a.kt", 5, NEXT, second?.commentId)

        assertEquals(listOf("x", "y", "z"), listOf(first?.commentId, second?.commentId, third?.commentId))
    }

    fun testNextComment_caretMovedAwayFromLastJump_goesByCaretLine() {
        val comments = mapOf("a.kt" to listOf(5, 9, 20))

        val target = ReviewNavigation.nextComment(files, positions(comments), "a.kt", 12, NEXT, "a.kt:5")

        assertEquals(line("a.kt", 20), target)
    }

    fun testNextChange_inSameFile_jumpsToFollowingHunk() {
        val target = ReviewNavigation.nextChange(files, listOf(2, 15, 30), "b.kt", 15, NEXT)

        assertEquals(NavTarget.Line("b.kt", 30), target)
    }

    fun testNextChange_pastLastHunk_opensNextFileAtFirstChange() {
        val target = ReviewNavigation.nextChange(files, listOf(2, 15), "b.kt", 15, NEXT)

        assertEquals(NavTarget.FirstChange("c.kt"), target)
    }

    fun testPreviousChange_beforeFirstHunk_opensPreviousFileAtLastChange() {
        val target = ReviewNavigation.nextChange(files, listOf(2, 15), "a.kt", 2, PREVIOUS)

        assertEquals(NavTarget.LastChange("c.kt"), target)
    }

    fun testNextChange_singleFile_wrapsWithinFile() {
        val target = ReviewNavigation.nextChange(listOf("a.kt"), listOf(2, 15), "a.kt", 15, NEXT)

        assertEquals(NavTarget.Line("a.kt", 2), target)
    }

    fun testNextChange_noFileOpen_opensFirstFile() {
        assertEquals(NavTarget.FirstChange("a.kt"), ReviewNavigation.nextChange(files, emptyList(), null, -1, NEXT))
    }

    fun testPreviousChange_noFileOpen_opensLastFile() {
        assertEquals(NavTarget.LastChange("c.kt"), ReviewNavigation.nextChange(files, emptyList(), null, -1, PREVIOUS))
    }

    fun testNextChange_emptyReviewDiff_returnsNull() {
        assertNull(ReviewNavigation.nextChange(emptyList(), emptyList(), null, -1, NEXT))
    }
}
