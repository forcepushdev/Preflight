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

        assertTrue(md.indexOf("src/A.kt") < md.indexOf("src/B.kt"))
        assertTrue(md.contains("`src/A.kt:42`\n"))
        assertTrue(md.contains("`src/B.kt:8-10`\n"))
        assertTrue(md.contains("**User**\nToo long\n"))
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
        assertTrue(md.contains("> **Agent**\n> Fixed.\n> Now non-null.\n"))
    }

    fun testRender_snippetWithBackticks_usesLongerFence() {
        val md = CommentMarkdown.render(listOf(PreflightComment("README.md", 0, "c", anchorText = "```kotlin", id = "1")))

        assertTrue(md.contains("````markdown\n```kotlin\n````"))
    }

    fun testRender_marksResolved() {
        val md = CommentMarkdown.render(listOf(PreflightComment("a.kt", 0, "c", resolved = true, id = "1")))

        assertTrue(md.contains("`a.kt:1` (resolved)"))
    }

    fun testRender_separatesCommentsWithRule() {
        val md = CommentMarkdown.render(
            listOf(PreflightComment("a.kt", 0, "first", id = "1"), PreflightComment("a.kt", 5, "second", id = "2"))
        )

        assertEquals(2, Regex("(?m)^---$").findAll(md).count())
        assertTrue(md.indexOf("first") < md.indexOf("---") && md.indexOf("---") < md.indexOf("second"))
        assertTrue(md.endsWith("---\n"))
    }

    fun testRender_matchesAgreedLayout() {
        val md = CommentMarkdown.render(
            listOf(
                PreflightComment(
                    "Roles.kt", 98, "🟡 Rename it.\n\nVorschlag: `X_EXT`.", startLine = 98,
                    anchorText = "const val X = \"X\"", author = "agent",
                    replies = listOf(Reply("Umgesetzt.", "user")), id = "1"
                )
            )
        )

        val expected = """
            |# Review Comments
            |
            |`Roles.kt:99`
            |
            |```kotlin
            |const val X = "X"
            |```
            |
            |**Agent**
            |🟡 Rename it.
            |
            |Vorschlag: `X_EXT`.
            |
            |> **User**
            |> Umgesetzt.
            |
            |---
            |""".trimMargin()
        assertEquals(expected, md)
    }

    fun testRender_empty() {
        assertTrue(CommentMarkdown.render(emptyList()).contains("_No open comments._"))
    }
}
