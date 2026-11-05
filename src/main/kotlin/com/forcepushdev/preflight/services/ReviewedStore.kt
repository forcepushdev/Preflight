package com.forcepushdev.preflight.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.security.MessageDigest

/**
 * A file marked as reviewed. The mark holds as long as the file's content still hashes to
 * [contentHash] — new commits that leave the file untouched keep it, any edit voids it.
 * [contentHash] is null for a file reviewed as deleted.
 */
data class ReviewMark(
    val file: String,
    val contentHash: String?,
    val commit: String?,
    val reviewedAt: Long
)

@Service(Service.Level.PROJECT)
class ReviewedStore(private val project: Project) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<List<ReviewMark>>() {}.type

    fun markReviewed(file: String, commit: String?) {
        val marks = load().filterNot { it.file == file } +
            ReviewMark(file, contentHash(file), commit, System.currentTimeMillis())
        save(marks)
    }

    fun unmark(file: String) = save(load().filterNot { it.file == file })

    fun getMark(file: String): ReviewMark? = load().firstOrNull { it.file == file }

    /** Of [files], those whose content is unchanged since they were marked as reviewed. */
    fun reviewedAmong(files: Collection<String>): Set<String> {
        val marks = load().associateBy { it.file }
        return files.filterTo(mutableSetOf()) { file ->
            val mark = marks[file] ?: return@filterTo false
            mark.contentHash == contentHash(file)
        }
    }

    private fun contentHash(file: String): String? =
        project.basePath?.let { File(it, file) }?.takeIf { it.isFile }?.readBytes()?.let(::gitBlobHash)

    private fun storeFile(): File? = project.basePath?.let { File("$it/.preflight/reviewed.json") }

    private fun load(): List<ReviewMark> {
        val file = storeFile()?.takeIf { it.exists() } ?: return emptyList()
        return try {
            gson.fromJson<List<ReviewMark>>(file.readText(), listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(marks: List<ReviewMark>) {
        val file = storeFile() ?: return
        file.parentFile.mkdirs()
        file.writeText(gson.toJson(marks.sortedBy { it.file }))
    }

    companion object {
        /** Same id `git hash-object` gives the raw bytes, so a mark can be checked against git by hand. */
        internal fun gitBlobHash(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray())
            digest.update(bytes)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
