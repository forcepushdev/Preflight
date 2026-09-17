package com.forcepushdev.preflight.services

import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.vcsUtil.VcsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BranchDiffServiceTest : BasePlatformTestCase() {

    private lateinit var service: BranchDiffService

    override fun setUp() {
        super.setUp()
        service = BranchDiffService(project)
    }

    private fun change(path: String): Change = Change(null, CurrentContentRevision(VcsUtil.getFilePath(path, false)))

    fun testMergeChanges_noOverlap_returnsBoth() {
        val committed = listOf(change("a.kt"))
        val uncommitted = listOf(change("b.kt"))

        val result = service.mergeChanges(committed, uncommitted)

        assertEquals(2, result.size)
    }

    fun testMergeChanges_uncommittedWinsOnOverlap() {
        val committedChange = change("a.kt")
        val uncommittedChange = change("a.kt")

        val result = service.mergeChanges(listOf(committedChange), listOf(uncommittedChange))

        assertEquals(1, result.size)
        assertSame(uncommittedChange, result[0])
    }

    fun testMergeChanges_emptyUncommitted_returnsCommittedOnly_regression() {
        val committed = listOf(change("a.kt"), change("b.kt"))

        val result = service.mergeChanges(committed, emptyList())

        assertEquals(committed, result)
    }

    fun testMergeChanges_emptyCommitted_returnsUncommittedOnly() {
        val uncommitted = listOf(change("a.kt"))

        val result = service.mergeChanges(emptyList(), uncommitted)

        assertEquals(uncommitted, result)
    }

    fun testParseCommitLog_multipleLines_parsesShaAndSubjectAndTimestampInOrder() {
        val output = "aaa111\u0000fix: handle null anchors\u00001700000000\nbbb222\u0000feat: add commit picker\u00001700001000"

        val result = service.parseCommitLog(output)

        assertEquals(2, result.size)
        assertEquals(CommitInfo("aaa111", "fix: handle null anchors", 1700000000L), result[0])
        assertEquals(CommitInfo("bbb222", "feat: add commit picker", 1700001000L), result[1])
    }

    fun testParseCommitLog_emptyOutput_returnsEmptyList() {
        val result = service.parseCommitLog("")

        assertEquals(emptyList<CommitInfo>(), result)
    }

    fun testParseCommitLog_trailingBlankLine_isIgnored() {
        val output = "aaa111\u0000fix: handle null anchors\u00001700000000\n"

        val result = service.parseCommitLog(output)

        assertEquals(listOf(CommitInfo("aaa111", "fix: handle null anchors", 1700000000L)), result)
    }

    fun testParseCommitLog_missingTimestamp_defaultsToZero() {
        val output = "aaa111\u0000fix: handle null anchors"

        val result = service.parseCommitLog(output)

        assertEquals(listOf(CommitInfo("aaa111", "fix: handle null anchors", 0L)), result)
    }
}
