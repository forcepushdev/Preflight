package com.forcepushdev.preflight.services

import junit.framework.TestCase

class CommentMarkdownTest : TestCase() {

    fun testRender_groupsByFileAndUsesOneBasedLines() {
        val md = CommentMarkdown.render(
            listOf(
                PreflightComment("src/B.kt", 9, "Second file", startLine = 7, id = "2"),
                PreflightComment("src/A.kt", 41, "Too long", id = "1")
            )
        )

        assertTrue(md.indexOf("## `src/A.kt`") < md.indexOf("## `src/B.kt`"))
        assertTrue(md.contains("### Line 42\n"))
        assertTrue(md.contains("### Lines 8–10\n"))
        assertTrue(md.contains("**User:** Too long"))
    }

    fun testRender_includesCodeSnippetAndReplies() {
        val md = CommentMarkdown.render(
            listOf(
                PreflightComment(
                    "src/A.kt", 3, "Why nullable?", startLine = 2,
                    anchorText = "val x: Int?\nval y = x!!",
                    replies = listOf(Reply("Fixed.\nNow non-null.", "agent")),
                    id = "1"
                )
            )
        )

        assertTrue(md.contains("```kotlin\nval x: Int?\nval y = x!!\n```"))
        assertTrue(md.contains("> **Agent:** Fixed.\n> Now non-null."))
    }

    fun testRender_snippetWithBackticks_usesLongerFence() {
        val md = CommentMarkdown.render(listOf(PreflightComment("README.md", 0, "c", anchorText = "```kotlin", id = "1")))

        assertTrue(md.contains("````markdown\n```kotlin\n````"))
    }

    fun testRender_marksResolved() {
        val md = CommentMarkdown.render(listOf(PreflightComment("a.kt", 0, "c", resolved = true, id = "1")))

        assertTrue(md.contains("### Line 1 (resolved)"))
    }

    fun testRender_empty() {
        assertTrue(CommentMarkdown.render(emptyList()).contains("_No open comments._"))
    }
}
