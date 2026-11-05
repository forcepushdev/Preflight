package com.forcepushdev.preflight.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class ReviewedStoreTest : BasePlatformTestCase() {

    private lateinit var store: ReviewedStore
    private lateinit var base: File

    override fun setUp() {
        super.setUp()
        store = ReviewedStore(project)
        base = File(project.basePath!!).also { it.mkdirs() }
    }

    override fun tearDown() {
        File(base, ".preflight").deleteRecursively()
        File(base, "src").deleteRecursively()
        super.tearDown()
    }

    private fun write(path: String, text: String) =
        File(base, path).apply { parentFile.mkdirs() }.writeText(text)

    fun testGitBlobHash_matchesGitHashObject() {
        // printf 'hello\n' | git hash-object --stdin
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", ReviewedStore.gitBlobHash("hello\n".toByteArray()))
    }

    fun testMarkedFile_isReviewed() {
        write("src/A.kt", "a")

        store.markReviewed("src/A.kt", "abc123")

        assertEquals(setOf("src/A.kt"), store.reviewedAmong(listOf("src/A.kt")))
        assertEquals("abc123", store.getMark("src/A.kt")?.commit)
    }

    fun testUnchangedContent_staysReviewedAcrossCommits() {
        write("src/A.kt", "a")
        store.markReviewed("src/A.kt", "commit1")

        // A new commit elsewhere leaves the bytes of A.kt as they were.
        write("src/A.kt", "a")

        assertEquals(setOf("src/A.kt"), store.reviewedAmong(listOf("src/A.kt")))
    }

    fun testChangedContent_voidsReview() {
        write("src/A.kt", "a")
        store.markReviewed("src/A.kt", "commit1")

        write("src/A.kt", "b")

        assertEquals(emptySet<String>(), store.reviewedAmong(listOf("src/A.kt")))
    }

    fun testRevertedContent_isReviewedAgain() {
        write("src/A.kt", "a")
        store.markReviewed("src/A.kt", "commit1")
        write("src/A.kt", "b")

        write("src/A.kt", "a")

        assertEquals(setOf("src/A.kt"), store.reviewedAmong(listOf("src/A.kt")))
    }

    fun testDeletedFile_reviewedWhileStillDeleted() {
        store.markReviewed("src/Gone.kt", "commit1")

        assertEquals(setOf("src/Gone.kt"), store.reviewedAmong(listOf("src/Gone.kt")))

        write("src/Gone.kt", "back")
        assertEquals(emptySet<String>(), store.reviewedAmong(listOf("src/Gone.kt")))
    }

    fun testUnmark_removesReview() {
        write("src/A.kt", "a")
        store.markReviewed("src/A.kt", null)

        store.unmark("src/A.kt")

        assertEquals(emptySet<String>(), store.reviewedAmong(listOf("src/A.kt")))
        assertNull(store.getMark("src/A.kt"))
    }

    fun testMarks_persistToSeparateFile() {
        write("src/A.kt", "a")
        store.markReviewed("src/A.kt", "commit1")

        assertTrue(File(base, ".preflight/reviewed.json").readText().contains("\"commit\": \"commit1\""))
        assertEquals(setOf("src/A.kt"), ReviewedStore(project).reviewedAmong(listOf("src/A.kt")))
    }
}
