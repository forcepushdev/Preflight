package com.forcepushdev.preflight.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.Type

@Service(Service.Level.PROJECT)
class CommentStore(private val project: Project) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Reply::class.java, ReplyDeserializer())
        .create()
    private val listType = object : TypeToken<List<PreflightComment>>() {}.type
    private val comments: MutableList<PreflightComment> = loadFromDisk().toMutableList()

    internal var currentBranchOverride: String? = null
    internal var branchFilterOverride: Boolean? = null

    private fun currentBranch(): String =
        currentBranchOverride ?: project.service<BranchDiffService>().getRepository()?.currentBranchName ?: ""

    private fun isVisibleInCurrentBranch(comment: PreflightComment): Boolean {
        if (!isBranchFilterEnabled()) return true
        val branch = currentBranch()
        return comment.branch == branch || comment.branch == ""
    }

    private fun isBranchFilterEnabled(): Boolean =
        branchFilterOverride ?: project.service<ConfigStore>().load().branchFilter

    fun addComment(comment: PreflightComment) {
        reload()
        comments.removeAll { it.file == comment.file && it.line == comment.line }
        val branch = if (isBranchFilterEnabled()) comment.branch.ifEmpty { currentBranch() } else ""
        comments.add(comment.copy(branch = branch))
        saveToDisk()
    }

    fun removeComment(file: String, line: Int) {
        reload()
        comments.removeAll { it.file == file && it.line == line }
        saveToDisk()
    }

    fun getComments(): List<PreflightComment> = comments.filter { isVisibleInCurrentBranch(it) }

    fun getCommentsForFile(file: String): List<PreflightComment> =
        comments.filter { it.file == file && isVisibleInCurrentBranch(it) }

    fun resolveComment(file: String, line: Int) = updateComment(file, line) { it.copy(resolved = true) }

    fun reopenComment(file: String, line: Int) = updateComment(file, line) { it.copy(resolved = false) }

    fun addReply(file: String, line: Int, text: String, author: String = "user") =
        updateComment(file, line) { it.copy(replies = it.replies + Reply(text, author)) }

    fun editComment(file: String, line: Int, newText: String) =
        updateComment(file, line) { it.copy(comment = newText) }

    fun editReply(file: String, line: Int, replyIndex: Int, newText: String) =
        updateComment(file, line) { it.copy(replies = it.replies.toMutableList().also { r -> r[replyIndex] = r[replyIndex].copy(text = newText) }) }

    fun getStaleComments(currentDiffFiles: Set<String>): List<PreflightComment> =
        comments.filter { isVisibleInCurrentBranch(it) && it.file !in currentDiffFiles && !it.resolved }

    fun getAllBranchStaleComments(currentDiffFiles: Set<String>): List<PreflightComment> =
        comments.filter { it.file !in currentDiffFiles && !it.resolved }

    fun reload() {
        comments.clear()
        comments.addAll(loadFromDisk())
    }

    private fun updateComment(file: String, line: Int, update: (PreflightComment) -> PreflightComment) {
        reload()
        val idx = comments.indexOfFirst { it.file == file && it.line == line }
        if (idx >= 0) {
            comments[idx] = update(comments[idx])
            saveToDisk()
        }
    }

    private fun commentsFile(): File? =
        project.basePath?.let { File("$it/.preflight/comments.json") }

    private fun saveToDisk() {
        val file = commentsFile() ?: return
        file.parentFile.mkdirs()
        file.writeText(gson.toJson(comments))
    }

    private fun loadFromDisk(): List<PreflightComment> {
        val file = commentsFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            val raw: List<PreflightComment> = gson.fromJson(file.readText(), listType) ?: emptyList()
            @Suppress("SENSELESS_COMPARISON")
            raw.map {
                it.copy(
                    replies = it.replies ?: emptyList(),
                    author = it.author ?: "user"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

private class ReplyDeserializer : JsonDeserializer<Reply> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Reply =
        if (json.isJsonPrimitive) Reply(text = json.asString)
        else {
            val obj = json.asJsonObject
            Reply(
                text = obj.get("text")?.asString ?: "",
                author = obj.get("author")?.asString ?: "user"
            )
        }
}
