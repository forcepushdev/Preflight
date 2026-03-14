package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.BranchDiffService
import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.MainEditorCommentHandler
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.editor.DiffViewerVirtualFile
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

private data class BranchLeaf(val fullName: String, val displayName: String)

class PreflightToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<BranchDiffService>()
        val panel = PreflightPanel(project, service, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}

private class PreflightPanel(
    private val project: Project,
    private val service: BranchDiffService,
    private val disposable: Disposable
) : JPanel(BorderLayout()) {

    private val treeRoot = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val fileTree = object : Tree(treeModel) {
        override fun updateUI() {
            super.updateUI()
            (ui as? javax.swing.plaf.basic.BasicTreeUI)?.apply {
                leftChildIndent = 2
                rightChildIndent = 6
            }
        }
    }.apply {
        isRootVisible = false
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = FileChangeTreeCellRenderer(project.service<CommentStore>(), project.basePath ?: "")
    }
    private var selectedBaseBranch: String = service.getMainBranch()
    private var currentRepo = service.getRepository()
    private var currentMergeBase: String? = null
    private var diffProcessor = PreflightDiffProcessor().also { Disposer.register(disposable, it) }
    private val diffFile = PreflightDiffFile()
    private val commentStore = project.service<CommentStore>()
    private val gutterHandler = CommentGutterHandler(commentStore) { onCommentAdded() }
    private val outdatedPanel = OutdatedCommentsPanel(commentStore) { refresh() }
    private var currentFile: String? = null
    private var currentInlayManager: CommentInlayManager? = null
    private var currentRemoteBranches: List<String> = emptyList()
    private var currentLocalBranches: List<String> = emptyList()
    private val branchButton = JButton(selectedBaseBranch).apply {
        addActionListener { showBranchPopup() }
    }

    private fun buildBranchPopupTree(): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()

        if (currentLocalBranches.isNotEmpty()) {
            val localNode = DefaultMutableTreeNode("Local")
            for (branch in currentLocalBranches) {
                val parts = branch.split("/")
                var current = localNode
                for (i in 0 until parts.size - 1) {
                    val dirName = parts[i]
                    current = current.children().asSequence()
                        .filterIsInstance<DefaultMutableTreeNode>()
                        .firstOrNull { it.userObject == dirName }
                        ?: DefaultMutableTreeNode(dirName).also { current.add(it) }
                }
                current.add(DefaultMutableTreeNode(BranchLeaf(branch, parts.last())))
            }
            root.add(localNode)
        }

        val remoteGroups = currentRemoteBranches.groupBy { it.substringBefore("/") }
        for ((remoteName, branches) in remoteGroups) {
            val remoteNode = DefaultMutableTreeNode(remoteName)
            for (branch in branches) {
                val withoutRemote = branch.removePrefix("$remoteName/")
                val parts = withoutRemote.split("/")
                var current = remoteNode
                for (i in 0 until parts.size - 1) {
                    val dirName = parts[i]
                    current = current.children().asSequence()
                        .filterIsInstance<DefaultMutableTreeNode>()
                        .firstOrNull { it.userObject == dirName }
                        ?: DefaultMutableTreeNode(dirName).also { current.add(it) }
                }
                current.add(DefaultMutableTreeNode(BranchLeaf(branch, parts.last())))
            }
            root.add(remoteNode)
        }

        return root
    }

    private fun showBranchPopup() {
        val root = buildBranchPopupTree()
        val tree = Tree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            cellRenderer = BranchTreeCellRenderer()
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        }
        var i = 0
        while (i < tree.rowCount) tree.expandRow(i++)

        val scrollPane = JBScrollPane(tree).apply { preferredSize = Dimension(280, 300) }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scrollPane, tree)
            .setRequestFocus(true)
            .createPopup()

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val leaf = node.userObject as? BranchLeaf ?: return
                selectedBaseBranch = leaf.fullName
                branchButton.text = leaf.fullName
                popup.cancel()
                refresh()
            }
        })
        popup.showUnderneathOf(branchButton)
    }

    private fun buildTopPanel(toolbar: com.intellij.openapi.actionSystem.ActionToolbar): JPanel =
        JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(JPanel().apply {
                add(JLabel("Base: "))
                add(branchButton)
            }, BorderLayout.EAST)
        }

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "Preflight",
            DefaultActionGroup(object : AnAction("Refresh", "Reload changed files", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refresh()
            }),
            true
        ).also { it.targetComponent = this }

        val leftPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(fileTree), BorderLayout.CENTER)
            add(outdatedPanel, BorderLayout.SOUTH)
        }

        add(buildTopPanel(toolbar), BorderLayout.NORTH)
        add(leftPanel, BorderLayout.CENTER)

        fileTree.addTreeSelectionListener {
            val node = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val change = node.userObject as? Change ?: return@addTreeSelectionListener
            openDiff(change)
        }

        DumbService.getInstance(project).runWhenSmart { refresh() }
    }

    private fun openDiff(change: Change) {
        val repo = currentRepo ?: return
        val mergeBase = currentMergeBase ?: return
        val filePath = change.afterRevision?.file ?: change.beforeRevision?.file ?: return
        val relativePath = filePath.path.removePrefix(repo.root.path + "/")

        ApplicationManager.getApplication().executeOnPooledThread {
            val baseText = service.getFileContentAtRevision(relativePath, mergeBase)

            ApplicationManager.getApplication().invokeLater {
                if (Disposer.isDisposed(diffProcessor)) {
                    diffProcessor = PreflightDiffProcessor().also { Disposer.register(disposable, it) }
                }
                val fileType = change.virtualFile?.fileType
                    ?: FileTypeManager.getInstance().getFileTypeByFileName(filePath.name)
                val baseContent = DiffContentFactory.getInstance().create(project, baseText, fileType)
                val headContent = change.virtualFile
                    ?.let { DiffContentFactory.getInstance().create(project, it) }
                    ?: DiffContentFactory.getInstance().create("")
                diffProcessor.setRequest(
                    SimpleDiffRequest("Diff: $relativePath", baseContent, headContent, "base", "HEAD")
                ) {
                    val editor = currentEditor()
                    if (editor != null) {
                        gutterHandler.attach(editor, relativePath, disposable)
                        currentInlayManager?.let { Disposer.dispose(it) }
                        currentInlayManager = CommentInlayManager(editor, commentStore, relativePath, disposable)
                        project.service<MainEditorCommentHandler>().registerExternalEditor(editor)
                    }
                }
                currentFile = relativePath
                DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, true)
            }
        }
    }

    private fun currentEditor(): EditorEx? = diffProcessor.editor2()

    private fun onCommentAdded() {
        val editor = currentEditor() ?: return
        val file = currentFile ?: return
        gutterHandler.attach(editor, file, disposable)
        currentInlayManager?.refresh()
        project.service<MainEditorCommentHandler>().refreshFile(file)
        refresh()
    }

    private fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val repo = service.getRepository()
            val (remote, local) = service.getAllBranches()
            val mergeBase = if (repo != null) service.getMergeBase(selectedBaseBranch) else null
            val changes = if (repo != null && mergeBase != null) service.getChangedFiles(selectedBaseBranch) else emptyList()

            ApplicationManager.getApplication().invokeLater {
                val previousBranch = selectedBaseBranch
                currentRemoteBranches = remote
                currentLocalBranches = local
                val allBranches = local + remote
                val toSelect = allBranches.firstOrNull { it == previousBranch } ?: local.firstOrNull() ?: remote.firstOrNull()
                if (toSelect != null) {
                    selectedBaseBranch = toSelect
                    branchButton.text = toSelect
                }
                currentRepo = repo
                currentMergeBase = mergeBase
                rebuildTree(repo?.root?.path, changes)
                val repoRoot = repo?.root?.path
                val changedFilePaths = changes.mapNotNull { change ->
                    val path = change.afterRevision?.file?.path
                        ?: change.beforeRevision?.file?.path
                    if (repoRoot != null && path != null) path.removePrefix("$repoRoot/") else path
                }.toSet()
                outdatedPanel.refresh(
                    commentStore.getStaleComments(changedFilePaths),
                    commentStore.getAllBranchStaleComments(changedFilePaths)
                )
            }
        }
    }

    private fun rebuildTree(repoRoot: String?, changes: List<Change>) {
        treeRoot.removeAllChildren()

        for (change in changes) {
            val filePath = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: continue
            val relative = if (repoRoot != null) filePath.removePrefix("$repoRoot/") else filePath
            val parts = relative.split("/")

            var current = treeRoot
            for (i in 0 until parts.size - 1) {
                val dirName = parts[i]
                current = current.children().asSequence()
                    .filterIsInstance<DefaultMutableTreeNode>()
                    .firstOrNull { it.userObject == dirName }
                    ?: DefaultMutableTreeNode(dirName).also { current.add(it) }
            }
            current.add(DefaultMutableTreeNode(change))
        }

        treeRoot.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().forEach {
            if (it.userObject is String) compactSingleChildDirs(it)
        }

        treeModel.reload()
        expandAll()
    }

    private fun compactSingleChildDirs(node: DefaultMutableTreeNode) {
        while (node.childCount == 1) {
            val only = node.getChildAt(0) as? DefaultMutableTreeNode ?: break
            if (only.userObject !is String) break
            node.userObject = "${node.userObject}/${only.userObject}"
            val grandchildren = (0 until only.childCount).map { only.getChildAt(it) as DefaultMutableTreeNode }
            node.removeAllChildren()
            grandchildren.forEach { node.add(it) }
        }
        node.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().forEach {
            if (it.userObject is String) compactSingleChildDirs(it)
        }
    }

    private fun expandAll() {
        var i = 0
        while (i < fileTree.rowCount) {
            fileTree.expandRow(i++)
        }
    }

    @Suppress("UnstableApiUsage")
    private inner class PreflightDiffProcessor : DiffRequestProcessor(project) {
        private var pendingRequest: DiffRequest? = null
        private var onApplied: (() -> Unit)? = null

        fun setRequest(request: DiffRequest, onApplied: (() -> Unit)? = null) {
            this.onApplied = onApplied
            pendingRequest = request
            ApplicationManager.getApplication().invokeLater { updateRequest(false, null) }
        }

        override fun updateRequest(force: Boolean, scrollToPolicy: DiffUserDataKeysEx.ScrollToPolicy?) {
            pendingRequest?.let {
                applyRequest(it, force, scrollToPolicy)
                ApplicationManager.getApplication().invokeLater {
                    onApplied?.invoke()
                    onApplied = null
                }
            }
        }

        fun editor2(): EditorEx? = (activeViewer as? TwosideTextDiffViewer)?.editor2
    }

    private inner class PreflightDiffFile : DiffViewerVirtualFile("Preflight Diff") {
        override fun createViewer(project: Project): DiffEditorViewer = diffProcessor
    }
}

private fun leafCount(node: DefaultMutableTreeNode): Int =
    node.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().sumOf {
        if (it.isLeaf) 1 else leafCount(it)
    }

private class BranchTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: javax.swing.JTree, value: Any?, selected: Boolean,
        expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val obj = node.userObject) {
            is BranchLeaf -> append(obj.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            is String -> {
                icon = AllIcons.Nodes.Folder
                append(obj, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }
    }
}

private class FileChangeTreeCellRenderer(
    private val commentStore: CommentStore,
    private val basePath: String
) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: javax.swing.JTree, value: Any?, selected: Boolean,
        expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val obj = node.userObject) {
            is Change -> {
                val filePath = obj.afterRevision?.file ?: obj.beforeRevision?.file
                val name = filePath?.name ?: return
                val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
                icon = fileType.icon
                append(name, fileStatusAttributes(obj, filePath.path))
                if (!selected && filePath.path.contains("/test/")) {
                    background = JBColor(Color(0xC8E6C9), Color(0x2D4A2D))
                    isOpaque = true
                } else {
                    isOpaque = false
                }
                val relPath = filePath.path.removePrefix("$basePath/")
                val unresolved = commentStore.getCommentsForFile(relPath).count { !it.resolved }
                if (unresolved > 0) {
                    append("  ● $unresolved", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.ORANGE))
                }
            }
            is String -> {
                icon = AllIcons.Nodes.Folder
                append(obj, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val count = leafCount(node)
                append("  $count ${if (count == 1) "file" else "files"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    private fun fileStatusAttributes(change: Change, path: String): SimpleTextAttributes =
        when (change.type) {
            Change.Type.NEW -> SimpleTextAttributes(
                SimpleTextAttributes.STYLE_PLAIN,
                JBColor(Color(0x6AAB69), Color(0x8FD08F))
            )
            Change.Type.DELETED -> SimpleTextAttributes(
                SimpleTextAttributes.STYLE_PLAIN,
                JBColor(Color(0xC42B1C), Color(0xFF6B68))
            )
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
}
