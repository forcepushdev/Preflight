package com.forcepushdev.preflight.services

import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.toolWindow.CommentGutterHandler
import com.forcepushdev.preflight.toolWindow.CommentInlayManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListAdapter
import com.intellij.openapi.vcs.changes.ChangeListManager
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

@Service(Service.Level.PROJECT)
class MainEditorCommentHandler(private val project: Project) : EditorFactoryListener {

    private val managers = mutableMapOf<Editor, CommentInlayManager>()
    private val editorDisposables = mutableMapOf<Editor, Disposable>()
    private val gutterHandlers = mutableMapOf<Editor, CommentGutterHandler>()
    private val gutterDisposables = mutableMapOf<Editor, Disposable>()
    private val externalEditors = Collections.newSetFromMap(WeakHashMap<Editor, Boolean>())

    @Volatile
    private var reviewDiffPaths: Set<String> = emptySet()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(this, project)
        startCommentsFileWatcher()
        ChangeListManager.getInstance(project).addChangeListListener(object : ChangeListAdapter() {
            override fun changeListUpdateDone() = refreshReviewDiffPaths()
        })
        refreshReviewDiffPaths()
    }

    /**
     * Files a comment can be added to via the gutter "+" button: everything in the current
     * Review Diff, committed or uncommitted. Recomputed off the EDT and cached, since it drives
     * a per-editor-open check and a git diff is too slow to run on every file open.
     */
    fun refreshReviewDiffPaths() {
        ApplicationManager.getApplication().executeOnPooledThread {
            reviewDiffPaths = project.service<BranchDiffService>().getChangedFiles()
                .mapNotNull { it.afterRevision?.file?.path ?: it.beforeRevision?.file?.path }
                .toSet()
            ApplicationManager.getApplication().invokeLater { attachGuttersForOpenEditors() }
        }
    }

    private fun attachGuttersForOpenEditors() {
        val basePath = project.basePath ?: return
        EditorFactory.getInstance().allEditors
            .filterIsInstance<EditorEx>()
            .filter { it !in externalEditors && it !in gutterHandlers }
            .forEach { editor ->
                val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return@forEach
                if (vf.isInLocalFileSystem && vf.path in reviewDiffPaths) {
                    attachGutter(editor, vf.path.removePrefix("$basePath/"))
                }
            }
    }

    private fun attachGutter(editor: EditorEx, relativePath: String) {
        val store = project.service<CommentStore>()
        val disposable = Disposer.newDisposable().also { Disposer.register(project, it) }
        val handler = CommentGutterHandler(store) { refreshFile(relativePath) }
        handler.attach(editor, relativePath, disposable)
        gutterHandlers[editor] = handler
        gutterDisposables.remove(editor)?.let { Disposer.dispose(it) }
        gutterDisposables[editor] = disposable
    }

    private fun startCommentsFileWatcher() {
        val dir = File("${project.basePath}/.preflight")
        if (!dir.exists()) dir.mkdirs()
        val watchService = FileSystems.getDefault().newWatchService()
        dir.toPath().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "preflight-file-watcher").also { it.isDaemon = true } }
        executor.submit {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val key = watchService.take()
                    val hasCommentsChange = key.pollEvents().any { it.context()?.toString() == "comments.json" }
                    key.reset()
                    if (hasCommentsChange) {
                        ApplicationManager.getApplication().invokeLater { refreshAllInlays() }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                watchService.close()
            }
        }
        Disposer.register(project) {
            executor.shutdownNow()
        }
    }

    fun registerExternalEditor(editor: Editor) {
        externalEditors.add(editor)
        editorDisposables.remove(editor)?.let { d ->
            managers.remove(editor)?.dispose()
            Disposer.dispose(d)
        }
        (editor as? EditorEx)?.let { ex -> gutterHandlers.remove(editor)?.detach(ex) }
        gutterDisposables.remove(editor)?.let { Disposer.dispose(it) }
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        if (event.editor in externalEditors) return
        val editor = event.editor as? EditorEx ?: return
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!virtualFile.isInLocalFileSystem) return
        val basePath = project.basePath ?: return
        if (!virtualFile.path.startsWith("$basePath/")) return
        val relativePath = virtualFile.path.removePrefix("$basePath/")
        if (virtualFile.path in reviewDiffPaths) {
            attachGutter(editor, relativePath)
        }
        val store = project.service<CommentStore>()
        if (store.getCommentsForFile(relativePath).isEmpty()) return
        val disposable = Disposer.newDisposable().also { Disposer.register(project, it) }
        editorDisposables[editor] = disposable
        managers[editor] = CommentInlayManager(editor, store, relativePath, disposable)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        managers.remove(editor)
        editorDisposables.remove(editor)?.let { Disposer.dispose(it) }
        gutterHandlers.remove(editor)
        gutterDisposables.remove(editor)?.let { Disposer.dispose(it) }
    }

    fun refreshAllInlays() {
        refreshReviewDiffPaths()
        val store = project.service<CommentStore>()
        store.reload()
        val basePath = project.basePath ?: return
        managers.entries.toList().forEach { (editor, manager) ->
            val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return@forEach
            val relativePath = virtualFile.path.removePrefix("$basePath/")
            if (store.getCommentsForFile(relativePath).isEmpty()) {
                manager.dispose()
                managers.remove(editor)
                editorDisposables.remove(editor)?.let { Disposer.dispose(it) }
            } else {
                manager.refresh()
            }
        }
        EditorFactory.getInstance().allEditors
            .filterIsInstance<EditorEx>()
            .filter { editor ->
                val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return@filter false
                val path = vf.path.removePrefix("$basePath/")
                vf.isInLocalFileSystem && !managers.containsKey(editor) && editor !in externalEditors
                    && store.getCommentsForFile(path).isNotEmpty()
            }
            .forEach { editor ->
                val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return@forEach
                val path = vf.path.removePrefix("$basePath/")
                val disposable = Disposer.newDisposable().also { Disposer.register(project, it) }
                editorDisposables[editor] = disposable
                managers[editor] = CommentInlayManager(editor, store, path, disposable)
            }
    }

    fun refreshFile(file: String) {
        val store = project.service<CommentStore>()
        val basePath = project.basePath ?: return
        managers.entries.toList().forEach { (editor, manager) ->
            val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return@forEach
            val relativePath = virtualFile.path.removePrefix("$basePath/")
            if (relativePath == file) {
                if (store.getCommentsForFile(file).isEmpty()) {
                    manager.dispose()
                    managers.remove(editor)
                    editorDisposables.remove(editor)?.let { Disposer.dispose(it) }
                } else {
                    manager.refresh()
                }
            }
        }
        val editorEx = EditorFactory.getInstance().allEditors
            .filterIsInstance<EditorEx>()
            .filter { editor ->
                val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return@filter false
                vf.isInLocalFileSystem && vf.path.removePrefix("$basePath/") == file && !managers.containsKey(editor) && editor !in externalEditors
            }
        editorEx.forEach { editor ->
            if (store.getCommentsForFile(file).isNotEmpty()) {
                val disposable = Disposer.newDisposable().also { Disposer.register(project, it) }
                editorDisposables[editor] = disposable
                managers[editor] = CommentInlayManager(editor, store, file, disposable)
            }
        }
    }
}
