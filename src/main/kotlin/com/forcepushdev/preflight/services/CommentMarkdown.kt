package com.forcepushdev.preflight.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/** Renders review comments as Markdown, e.g. to paste into a PR, a ticket or an agent prompt. */
object CommentMarkdown {

    fun render(comments: List<PreflightComment>): String {
        if (comments.isEmpty()) return "# Review Comments\n\n_No open comments._\n"
        val ordered = comments.sortedWith(compareBy({ it.file }, { it.startLine ?: it.line }))
        return "# Review Comments\n\n" + ordered.joinToString("") { renderComment(it) + "\n---\n\n" }.trimEnd() + "\n"
    }

    private fun renderComment(comment: PreflightComment): String {
        val out = StringBuilder()
        // Stored lines are 0-based; editors and PR tools count from 1.
        val start = (comment.startLine ?: comment.line) + 1
        val end = comment.line + 1
        val location = if (start == end) "${comment.file}:$start" else "${comment.file}:$start-$end"
        val status = if (comment.resolved) " (resolved)" else ""
        out.append("`").append(location).append('`').append(status).append('\n')
        comment.anchorText?.takeIf { it.isNotBlank() }?.let { code ->
            val fence = fenceFor(code)
            out.append('\n').append(fence).append(languageOf(comment.file)).append('\n')
                .append(code.trimEnd('\n')).append('\n').append(fence).append('\n')
        }
        out.append("\n**").append(authorLabel(comment.author)).append("**\n").append(comment.comment.trim()).append('\n')
        comment.replies.forEach { reply ->
            out.append("\n> **").append(authorLabel(reply.author)).append("**\n")
            reply.text.trim().lines().forEach { line -> out.append(if (line.isEmpty()) ">" else "> $line").append('\n') }
        }
        return out.toString()
    }

    private fun authorLabel(author: String?) = if (author.equals("agent", ignoreCase = true)) "Agent" else "User"

    // A fence longer than any backtick run inside the snippet, so Markdown in the code can't close it.
    private fun fenceFor(code: String): String {
        val longestRun = Regex("`+").findAll(code).maxOfOrNull { it.value.length } ?: 0
        return "`".repeat(maxOf(3, longestRun + 1))
    }

    private fun languageOf(file: String): String = when (file.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "ts", "tsx" -> "typescript"
        "js", "jsx" -> "javascript"
        "py" -> "python"
        "go" -> "go"
        "rs" -> "rust"
        "xml" -> "xml"
        "json" -> "json"
        "yml", "yaml" -> "yaml"
        "md" -> "markdown"
        "sql" -> "sql"
        "sh" -> "bash"
        else -> ""
    }
}

/**
 * Keeps `.preflight/comments.md`, a readable Markdown view of the open comments, next to
 * comments.json. Once created it is rewritten whenever the comments change.
 */
@Service(Service.Level.PROJECT)
class CommentMarkdownExporter(private val project: Project) {

    fun markdownFile(): File? = project.basePath?.let { File("$it/.preflight/comments.md") }

    fun export(): File? {
        val file = markdownFile() ?: return null
        val open = project.service<CommentStore>().also { it.reload() }.getComments().filter { !it.resolved }
        file.parentFile.mkdirs()
        file.writeText(CommentMarkdown.render(open))
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        return file
    }

    fun refreshIfPresent() {
        if (markdownFile()?.exists() == true) export()
    }
}
