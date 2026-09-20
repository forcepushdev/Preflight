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
import java.util.UUID

@Service(Service.Level.PROJECT)
class CommentStore(private val project: Project) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Reply::class.java, ReplyDeserializer())
        .create()
    private val listType = object : TypeToken<List<PreflightComment>>() {}.type
    private val comments: MutableList<PreflightComment> = loadFromDisk().toMutableList()

    fun addComment(comment: PreflightComment) {
        reload()
        comments.removeAll { it.id == comment.id }
        comments.add(comment)
        saveToDisk()
    }

    fun removeComment(id: String) {
        reload()
        comments.removeAll { it.id == id }
        saveToDisk()
    }

    fun removeAllComments() {
        reload()
        comments.clear()
        saveToDisk()
    }

    fun getComments(): List<PreflightComment> = comments.toList()

    fun getComment(id: String): PreflightComment? = comments.firstOrNull { it.id == id }

    fun getCommentsForFile(file: String): List<PreflightComment> =
        comments.filter { it.file == file }

    fun resolveComment(id: String) = updateComment(id) { it.copy(resolved = true) }

    fun reopenComment(id: String) = updateComment(id) { it.copy(resolved = false) }

    fun addReply(id: String, text: String, author: String = "user") =
        updateComment(id) { it.copy(replies = it.replies + Reply(text, author)) }

    fun editComment(id: String, newText: String) =
        updateComment(id) { it.copy(comment = newText) }

    fun editReply(id: String, replyIndex: Int, newText: String) =
        updateComment(id) { it.copy(replies = it.replies.toMutableList().also { r -> r[replyIndex] = r[replyIndex].copy(text = newText) }) }

    fun updateAnchor(id: String, startLine: Int, line: Int, anchorText: String?) =
        updateComment(id) { it.copy(startLine = startLine, line = line, anchorText = anchorText) }

    fun getStaleComments(currentDiffFiles: Set<String>, orphanedIds: Set<String> = emptySet()): List<PreflightComment> =
        comments.filter { (it.file !in currentDiffFiles || it.id in orphanedIds) && !it.resolved }

    fun reload() {
        comments.clear()
        comments.addAll(loadFromDisk())
    }

    private fun updateComment(id: String, update: (PreflightComment) -> PreflightComment) {
        reload()
        val idx = comments.indexOfFirst { it.id == id }
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
            val migrated = raw.map {
                it.copy(
                    replies = it.replies ?: emptyList(),
                    author = it.author ?: "user",
                    id = it.id ?: UUID.randomUUID().toString()
                )
            }
            if (migrated != raw) file.writeText(gson.toJson(migrated))
            migrated
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
