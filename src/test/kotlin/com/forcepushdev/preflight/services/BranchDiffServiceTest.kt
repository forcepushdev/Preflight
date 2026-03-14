package com.forcepushdev.preflight.services

import com.forcepushdev.preflight.services.BranchDiffService
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BranchDiffServiceTest : BasePlatformTestCase() {

    private lateinit var service: BranchDiffService

    override fun setUp() {
        super.setUp()
        service = BranchDiffService(project)
    }

    fun testDetectMainBranch_prefersMain() {
        assertEquals("origin/main", service.detectMainBranch(listOf("origin/main", "origin/master")))
    }

    fun testDetectMainBranch_fallsBackToMaster() {
        assertEquals("origin/master", service.detectMainBranch(listOf("origin/master", "origin/develop")))
    }

    fun testDetectMainBranch_defaultsToMainWhenNeitherPresent() {
        assertEquals("main", service.detectMainBranch(listOf("origin/develop", "origin/feature")))
    }

    fun testGetAllBranches_returnsEmptyListWhenNoRepo() {
        val (remote, local) = service.getAllBranches()
        assertTrue(remote.isEmpty())
        assertTrue(local.isEmpty())
    }

    fun testParseMergeBase_returnsTrimmedSha() {
        assertEquals("abc123def456", service.parseMergeBase("abc123def456\n"))
    }

    fun testParseMergeBase_returnsNullWhenEmpty() {
        assertNull(service.parseMergeBase(""))
    }
}
