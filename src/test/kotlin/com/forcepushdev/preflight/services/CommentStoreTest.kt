package com.forcepushdev.preflight.services

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class CommentStoreTest : BasePlatformTestCase() {

    private lateinit var store: CommentStore

    override fun setUp() {
        super.setUp()
        store = CommentStore(project)
    }

    override fun tearDown() {
        project.basePath?.let { File("$it/.preflight").deleteRecursively() }
        super.tearDown()
    }

    fun testAddAndGetComments() {
        val comment = PreflightComment("src/Foo.kt", 42, "Too long")

        store.addComment(comment)

        assertEquals(listOf(comment), store.getComments())
    }

    fun testGetCommentsForFile_filtersCorrectly() {
        store.addComment(PreflightComment("src/Foo.kt", 10, "A"))
        store.addComment(PreflightComment("src/Bar.kt", 20, "B"))

        val result = store.getCommentsForFile("src/Foo.kt")

        assertEquals(1, result.size)
        assertEquals("src/Foo.kt", result[0].file)
    }

    fun testRemoveComment() {
        val comment = PreflightComment("src/Foo.kt", 42, "Test")
        store.addComment(comment)

        store.removeComment(comment.id)

        assertTrue(store.getComments().isEmpty())
    }

    fun testPreflightComment_hasUniqueIdByDefault() {
        val a = PreflightComment("src/Foo.kt", 1, "A")
        val b = PreflightComment("src/Foo.kt", 1, "B")

        assertTrue(a.id.isNotBlank())
        assertTrue(a.id != b.id)
    }

    fun testAddComment_allowsTwoCommentsOnSameLine() {
        store.addComment(PreflightComment("src/Foo.kt", 5, "First"))
        store.addComment(PreflightComment("src/Foo.kt", 5, "Second"))

        assertEquals(2, store.getCommentsForFile("src/Foo.kt").size)
    }

    fun testLoadFromDisk_backfillsMissingId_stableAcrossReload() {
        val json = """[{"file":"src/Foo.kt","line":1,"comment":"legacy"}]"""
        project.basePath?.let { File("$it/.preflight").also { d -> d.mkdirs() } }
        File("${project.basePath}/.preflight/comments.json").writeText(json)

        val loaded = CommentStore(project)
        val id = loaded.getComments()[0].id
        assertTrue(id.isNotBlank())

        loaded.reload()

        assertEquals(id, loaded.getComments()[0].id)
    }

    fun testPersistsToJson_reloadedByNewInstance() {
        store.addComment(PreflightComment("src/Foo.kt", 42, "Test"))

        val store2 = CommentStore(project)

        assertEquals(1, store2.getComments().size)
        assertEquals("src/Foo.kt", store2.getComments()[0].file)
    }

    fun testAddComment_createsPreflightDirectory() {
        store.addComment(PreflightComment("src/Foo.kt", 1, "X"))

        assertTrue(File("${project.basePath}/.preflight/comments.json").exists())
    }

    fun testGetStaleComments_fileNotInDiff() {
        store.addComment(PreflightComment("src/Gone.kt", 5, "Old"))

        val stale = store.getStaleComments(setOf("src/Foo.kt"))

        assertEquals(1, stale.size)
        assertEquals("src/Gone.kt", stale[0].file)
    }

    fun testGetStaleComments_fileInDiff_notStale() {
        store.addComment(PreflightComment("src/Foo.kt", 5, "Current"))

        val stale = store.getStaleComments(setOf("src/Foo.kt"))

        assertTrue(stale.isEmpty())
    }

    fun testGetStaleComments_emptyDiff_allStale() {
        store.addComment(PreflightComment("src/Foo.kt", 5, "X"))
        store.addComment(PreflightComment("src/Bar.kt", 10, "Y"))

        val stale = store.getStaleComments(emptySet())

        assertEquals(2, stale.size)
    }

    fun testPreflightComment_defaultsResolvedFalse_repliesEmpty() {
        val comment = PreflightComment("src/Foo.kt", 1, "text")

        assertFalse(comment.resolved)
        assertTrue(comment.replies.isEmpty())
    }

    fun testPreflightComment_startLineDefaultsNull() {
        val comment = PreflightComment("src/Foo.kt", 1, "text")

        assertNull(comment.startLine)
    }

    fun testPreflightComment_startLinePersists() {
        store.addComment(PreflightComment("src/Foo.kt", 5, "multi", startLine = 3))

        val store2 = CommentStore(project)

        assertEquals(3, store2.getComments()[0].startLine)
    }

    fun testResolveComment_setsResolvedTrue() {
        val comment = PreflightComment("src/Foo.kt", 1, "text")
        store.addComment(comment)

        store.resolveComment(comment.id)

        assertTrue(store.getComments()[0].resolved)
    }

    fun testReopenComment_setsResolvedFalse() {
        val comment = PreflightComment("src/Foo.kt", 1, "text", resolved = true)
        store.addComment(comment)

        store.reopenComment(comment.id)

        assertFalse(store.getComments()[0].resolved)
    }

    fun testAddReply_appendsToList() {
        val comment = PreflightComment("src/Foo.kt", 1, "text")
        store.addComment(comment)

        store.addReply(comment.id, "my reply")

        assertEquals(listOf(Reply("my reply")), store.getComments()[0].replies)
    }

    fun testAddReply_persists() {
        val comment = PreflightComment("src/Foo.kt", 1, "text")
        store.addComment(comment)
        store.addReply(comment.id, "my reply")

        val store2 = CommentStore(project)

        assertEquals(listOf(Reply("my reply")), store2.getComments()[0].replies)
    }

    fun testAddReply_withAgentAuthor() {
        val comment = PreflightComment("src/Foo.kt", 1, "text")
        store.addComment(comment)

        store.addReply(comment.id, "agent reply", author = "agent")

        assertEquals(Reply("agent reply", "agent"), store.getComments()[0].replies[0])
    }

    fun testReplyDeserializer_handlesLegacyStringFormat() {
        val json = """[{"file":"src/Foo.kt","line":1,"comment":"text","replies":["old reply"]}]"""
        project.basePath?.let { java.io.File("$it/.preflight/comments.json").also { f -> f.parentFile.mkdirs(); f.writeText(json) } }

        val loaded = CommentStore(project)

        assertEquals(Reply("old reply", "user"), loaded.getComments()[0].replies[0])
    }

    fun testReload_readsFromDisk() {
        store.addComment(PreflightComment("src/Foo.kt", 1, "test"))

        store.reload()

        assertEquals(1, store.getComments().size)
    }

    fun testGetStaleComments_excludesResolved() {
        store.addComment(PreflightComment("src/Gone.kt", 5, "Old", resolved = true))
        store.addComment(PreflightComment("src/Gone.kt", 6, "Active"))

        val stale = store.getStaleComments(emptySet())

        assertEquals(1, stale.size)
        assertFalse(stale[0].resolved)
    }

    fun testGetStaleComments_orphanedAnchor_staleEvenWhenFileInDiff() {
        val comment = PreflightComment("src/Foo.kt", 5, "Current")
        store.addComment(comment)

        val stale = store.getStaleComments(setOf("src/Foo.kt"), orphanedIds = setOf(comment.id))

        assertEquals(1, stale.size)
        assertEquals(comment.id, stale[0].id)
    }

    fun testGetStaleComments_orphanedAnchor_excludesResolved() {
        val comment = PreflightComment("src/Foo.kt", 5, "Current", resolved = true)
        store.addComment(comment)

        val stale = store.getStaleComments(setOf("src/Foo.kt"), orphanedIds = setOf(comment.id))

        assertTrue(stale.isEmpty())
    }
}
