package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.DiffBase
import org.junit.Assert.assertEquals
import org.junit.Test

class DiffBaseSelectionTest {

    @Test
    fun formatDiffBaseLabel_branch_returnsBranchName() {
        val result = formatDiffBaseLabel(DiffBase.Branch("main"))

        assertEquals("main", result)
    }

    @Test
    fun formatDiffBaseLabel_commit_returnsShortShaAndSubject() {
        val result = formatDiffBaseLabel(DiffBase.Commit("a1b2c3d4e5f6", "fix: handle null anchors"))

        assertEquals("a1b2c3d — fix: handle null anchors", result)
    }

    @Test
    fun formatDiffBaseLabel_commit_shortShaAlreadyUnderSevenChars_isUsedAsIs() {
        val result = formatDiffBaseLabel(DiffBase.Commit("a1b", "wip"))

        assertEquals("a1b — wip", result)
    }

    @Test
    fun selectEffectiveDiffBase_commitReachable_keepsCurrent() {
        val current = DiffBase.Commit("a1b2c3d4e5f6", "fix: handle null anchors")
        val fallback = DiffBase.Branch("main")

        val result = selectEffectiveDiffBase(current, isReachable = true, fallback = fallback)

        assertEquals(current, result)
    }

    @Test
    fun selectEffectiveDiffBase_commitUnreachable_fallsBackToBranch() {
        val current = DiffBase.Commit("a1b2c3d4e5f6", "fix: handle null anchors")
        val fallback = DiffBase.Branch("main")

        val result = selectEffectiveDiffBase(current, isReachable = false, fallback = fallback)

        assertEquals(fallback, result)
    }

    @Test
    fun selectEffectiveDiffBase_branch_neverFallsBack() {
        val current = DiffBase.Branch("main")
        val fallback = DiffBase.Branch("main")

        val result = selectEffectiveDiffBase(current, isReachable = false, fallback = fallback)

        assertEquals(current, result)
    }

    @Test
    fun formatDiffBaseLabel_commit_longSubject_isTruncatedWithEllipsis() {
        val longSubject = "cascaded child entities and deselection upsert special case"

        val result = formatDiffBaseLabel(DiffBase.Commit("a1b2c3d4e5f6", longSubject))

        assertEquals("a1b2c3d — cascaded child entities and deselection…", result)
    }

    @Test
    fun formatDiffBaseTooltip_commit_returnsUntruncatedSubject() {
        val longSubject = "cascaded child entities and deselection upsert special case"

        val result = formatDiffBaseTooltip(DiffBase.Commit("a1b2c3d4e5f6", longSubject))

        assertEquals("a1b2c3d — $longSubject", result)
    }

    @Test
    fun formatCommitTimestamp_formatsAsLocalDateAndTime() {
        // 2024-01-15T10:30:00Z
        val result = formatCommitTimestamp(1705314600L)

        assertEquals(4, result.split("-")[0].length)
    }
}
