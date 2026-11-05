package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.BranchDiffService
import com.forcepushdev.preflight.services.CommentAnchorRegistry
import com.forcepushdev.preflight.services.CommentStore
import com.forcepushdev.preflight.services.CommitInfo
import com.forcepushdev.preflight.services.DiffBase
import com.forcepushdev.preflight.services.MainEditorCommentHandler
import com.forcepushdev.preflight.services.ReviewedStore
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListAdapter
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.PopupHandler
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
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
import java.awt.event.MouseEvent
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

private data class BranchLeaf(val fullName: String, val displayName: String)
private data class CommitLeaf(val sha: String, val subject: String, val commitEpochSeconds: Long)

private const val DIFF_BASE_POPUP_DIMENSION_KEY = "Preflight.DiffBasePopup"

private fun updateDiffBaseButton(button: JButton, diffBase: DiffBase) {
    button.text = formatDiffBaseLabel(diffBase)
    button.toolTipText = formatDiffBaseTooltip(diffBase)
}

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
) : JPanel(BorderLayout()), ReviewController {

    private val treeRoot = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val fileTree = object : Tree(treeModel) {
        // A press on a file's review checkbox toggles the mark instead of selecting (and opening) the file.
        override fun processMouseEvent(e: MouseEvent) {
            if (e.id == MouseEvent.MOUSE_PRESSED && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                val file = reviewCheckboxFileAt(e)
                if (file != null) {
                    e.consume()
                    toggleReviewed(file)
                    return
                }
            }
            super.processMouseEvent(e)
        }

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
        cellRenderer = FileChangeTreeCellRenderer(project.service<CommentStore>(), project.basePath ?: "",
            isShownInDiff = { it == currentFile },
            isReviewed = { it in reviewedFiles }
        )
    }
    private var selectedDiffBase: DiffBase = DiffBase.Branch(service.getMainBranch())
    private var lastActiveBaseBranch: String = service.getMainBranch()
    private var lastSelectedCommitSha: String? = null
    private var lastActiveDiffBaseTabIndex: Int = 0
    private var currentRepo = service.getRepository()
    private var currentRevision: String? = null
    private var diffProcessor = PreflightDiffProcessor().also { Disposer.register(disposable, it) }
    private val diffFile = PreflightDiffFile()
    private val commentStore = project.service<CommentStore>()
    private val gutterHandler = CommentGutterHandler(commentStore) { onCommentAdded() }
    private val outdatedPanel = OutdatedCommentsPanel(commentStore) { refresh() }
    private var currentFile: String? = null
    private val reviewedStore = project.service<ReviewedStore>()
    // Files of the Review Diff whose review mark is still valid; recomputed off the EDT since it hashes file contents.
    @Volatile
    private var reviewedFiles: Set<String> = emptySet()
    @Volatile
    private var changedFiles: Set<String> = emptySet()
    // Set while the tree selection is changed programmatically, so it doesn't reopen the diff.
    private var restoringSelection = false
    // Where the diff opened by the next tree selection should scroll to; set by navigate().
    private var pendingScrollTarget: NavTarget? = null
    // Comment the last "next/previous comment" step landed on; see ReviewNavigation.nextComment.
    private var lastCommentJump: String? = null
    private var currentInlayManager: CommentInlayManager? = null
    private var currentRemoteBranches: List<String> = emptyList()
    private var currentLocalBranches: List<String> = emptyList()
    private var currentCommits: List<CommitInfo> = emptyList()
    private val diffBaseButton = JButton().apply {
        addActionListener { showDiffBasePopup() }
    }.also { updateDiffBaseButton(it, selectedDiffBase) }

    private fun buildBranchPopupTree(filter: String = ""): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        val matches = { branch: String -> branch.contains(filter.trim(), ignoreCase = true) }
        val localBranches = currentLocalBranches.filter(matches)
        val remoteBranches = currentRemoteBranches.filter(matches)

        if (localBranches.isNotEmpty()) {
            val localNode = DefaultMutableTreeNode("Local")
            for (branch in localBranches) {
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

        val remoteGroups = remoteBranches.groupBy { it.substringBefore("/") }
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

    private fun buildCommitPopupTree(): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        if (currentCommits.isEmpty()) {
            root.add(DefaultMutableTreeNode("Keine Commits seit main"))
        } else {
            for (commit in currentCommits) {
                root.add(DefaultMutableTreeNode(CommitLeaf(commit.sha, commit.subject, commit.commitEpochSeconds)))
            }
        }
        return root
    }

    /** Selects the tree node matching [predicate] without firing any selection listener — call
     * before listeners are attached, so restoring the last pick doesn't itself trigger a refresh. */
    private fun restoreSelection(tree: Tree, predicate: (Any?) -> Boolean) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        val enumeration = root.depthFirstEnumeration()
        while (enumeration.hasMoreElements()) {
            val node = enumeration.nextElement() as? DefaultMutableTreeNode ?: continue
            if (predicate(node.userObject)) {
                val path = TreePath(node.path)
                tree.selectionPath = path
                tree.scrollPathToVisible(path)
                return
            }
        }
    }

    private fun firstBranchLeafPath(tree: Tree): TreePath? {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return null
        return root.depthFirstEnumeration().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { it.userObject is BranchLeaf }
            ?.let { TreePath(it.path) }
    }

    private fun showDiffBasePopup() {
        val branchTree = Tree(DefaultTreeModel(buildBranchPopupTree())).apply {
            isRootVisible = false
            cellRenderer = BranchTreeCellRenderer()
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            // Fixed row height avoids Swing's variable-height row-bounds cache, which can be stale
            // on a freshly built tree and make the first click's hit-test resolve to the wrong row.
            rowHeight = JBUI.scale(20)
        }
        var i = 0
        while (i < branchTree.rowCount) branchTree.expandRow(i++)
        // Only pre-highlight when a Branch is actually the active diff base — if a Commit is
        // active, lastActiveBaseBranch is just the branch we'd fall back to, not what's showing.
        // Pre-selecting it anyway would mean clicking it (to switch back) selects a node the tree
        // already considers selected, which fires no TreeSelectionEvent and silently does nothing.
        restoreSelection(branchTree) {
            selectedDiffBase is DiffBase.Branch && it is BranchLeaf && it.fullName == lastActiveBaseBranch
        }

        val commitTree = object : Tree(DefaultTreeModel(buildCommitPopupTree())) {
            override fun getToolTipText(event: MouseEvent): String? {
                val path = getPathForLocation(event.x, event.y) ?: return null
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
                val leaf = node.userObject as? CommitLeaf ?: return null
                return formatDiffBaseTooltip(DiffBase.Commit(leaf.sha, leaf.subject))
            }
        }.apply {
            isRootVisible = false
            cellRenderer = CommitTreeCellRenderer()
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            rowHeight = JBUI.scale(20)
            javax.swing.ToolTipManager.sharedInstance().registerComponent(this)
        }
        i = 0
        while (i < commitTree.rowCount) commitTree.expandRow(i++)
        restoreSelection(commitTree) {
            selectedDiffBase is DiffBase.Commit && it is CommitLeaf && it.sha == lastSelectedCommitSha
        }

        val branchSearch = SearchTextField(false).apply {
            textEditor.emptyText.text = "Search branches"
        }
        branchSearch.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                branchTree.model = DefaultTreeModel(buildBranchPopupTree(branchSearch.text))
                var row = 0
                while (row < branchTree.rowCount) branchTree.expandRow(row++)
            }
        })
        branchSearch.textEditor.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    // Enter picks the first match, which makes a typed name plus Enter the fast path.
                    java.awt.event.KeyEvent.VK_ENTER -> firstBranchLeafPath(branchTree)?.let {
                        e.consume()
                        branchTree.selectionPath = it
                    }
                    java.awt.event.KeyEvent.VK_DOWN -> {
                        e.consume()
                        branchTree.requestFocusInWindow()
                    }
                }
            }
        })
        val branchPanel = JPanel(BorderLayout()).apply {
            add(branchSearch, BorderLayout.NORTH)
            add(JBScrollPane(branchTree), BorderLayout.CENTER)
        }

        val tabs = JTabbedPane().apply {
            addTab("Branches", branchPanel)
            addTab("Commits", JBScrollPane(commitTree))
            preferredSize = Dimension(320, 340)
            selectedIndex = lastActiveDiffBaseTabIndex.coerceIn(0, tabCount - 1)
        }
        tabs.addChangeListener { lastActiveDiffBaseTabIndex = tabs.selectedIndex }

        val focused = if (tabs.selectedIndex == 1) commitTree else branchSearch.textEditor
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(tabs, focused)
            .setRequestFocus(true)
            .setResizable(true)
            .setMinSize(Dimension(240, 200))
            .setDimensionServiceKey(project, DIFF_BASE_POPUP_DIMENSION_KEY, true)
            .createPopup()

        branchTree.addTreeSelectionListener { e ->
            val node = e.newLeadSelectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val leaf = node.userObject as? BranchLeaf ?: return@addTreeSelectionListener
            selectedDiffBase = DiffBase.Branch(leaf.fullName)
            lastActiveBaseBranch = leaf.fullName
            updateDiffBaseButton(diffBaseButton, selectedDiffBase)
            popup.cancel()
            refresh()
        }
        commitTree.addTreeSelectionListener { e ->
            val node = e.newLeadSelectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val leaf = node.userObject as? CommitLeaf ?: return@addTreeSelectionListener
            selectedDiffBase = DiffBase.Commit(leaf.sha, leaf.subject)
            lastSelectedCommitSha = leaf.sha
            updateDiffBaseButton(diffBaseButton, selectedDiffBase)
            popup.cancel()
            refresh()
        }
        popup.showUnderneathOf(diffBaseButton)
    }

    private fun buildTopPanel(toolbar: com.intellij.openapi.actionSystem.ActionToolbar): JPanel =
        JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(JPanel().apply {
                add(JLabel("Base: "))
                add(diffBaseButton)
            }, BorderLayout.EAST)
        }

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "Preflight",
            DefaultActionGroup(
                ActionManager.getInstance().getAction("Preflight.ToggleReviewed"),
                ActionManager.getInstance().getAction("Preflight.OpenCommentsMarkdown"),
                object : AnAction("Refresh", "Reload changed files and comments", AllIcons.Actions.Refresh) {
                    override fun actionPerformed(e: AnActionEvent) = reloadComments()
                },
                object : AnAction("Collapse All Comments", "Collapse all comment threads in the editor", AllIcons.Actions.Collapseall) {
                    override fun actionPerformed(e: AnActionEvent) = collapseAllComments()
                },
                object : AnAction("Delete All Comments", "Delete all Preflight comments", AllIcons.Actions.GC) {
                    override fun actionPerformed(e: AnActionEvent) = deleteAllComments()
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = commentStore.getComments().isNotEmpty()
                    }
                }
            ),
            true
        ).also { it.targetComponent = this }

        val leftPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(fileTree), BorderLayout.CENTER)
            add(outdatedPanel, BorderLayout.SOUTH)
        }

        add(buildTopPanel(toolbar), BorderLayout.NORTH)
        add(leftPanel, BorderLayout.CENTER)

        fileTree.addTreeSelectionListener {
            if (restoringSelection) return@addTreeSelectionListener
            val node = fileTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val change = node.userObject as? Change ?: return@addTreeSelectionListener
            val scrollTarget = pendingScrollTarget
            pendingScrollTarget = null
            openDiff(change, scrollTarget)
        }

        PopupHandler.installPopupMenu(fileTree, DefaultActionGroup(ActionManager.getInstance().getAction("Preflight.ToggleReviewed")), "PreflightFileTree")
        // Edits (once saved) can void a review mark; the change list update follows each save.
        ChangeListManager.getInstance(project).addChangeListListener(object : ChangeListAdapter() {
            override fun changeListUpdateDone() = recomputeReviewed()
        }, disposable)

        DumbService.getInstance(project).runWhenSmart { refresh() }
    }

    private fun openDiff(change: Change, scrollTarget: NavTarget? = null) {
        val repo = currentRepo ?: return
        val revision = currentRevision ?: return
        val filePath = change.afterRevision?.file ?: change.beforeRevision?.file ?: return
        val relativePath = filePath.path.removePrefix(repo.root.path + "/")
        // On a rename/move, the file lived under a different path at the base revision — reading
        // it back by the current (after) path fails and silently yields an empty base, making a
        // renamed file look like a brand new addition instead of showing its real prior content.
        val baseRelativePath = change.beforeRevision?.file?.path?.removePrefix(repo.root.path + "/")

        ApplicationManager.getApplication().executeOnPooledThread {
            val baseText = baseRelativePath?.let { service.getFileContentAtRevision(it, revision) } ?: ""

            ApplicationManager.getApplication().invokeLater {
                if (diffProcessor.isDisposed) {
                    diffProcessor = PreflightDiffProcessor().also { Disposer.register(disposable, it) }
                }
                val fileType = change.virtualFile?.fileType
                    ?: FileTypeManager.getInstance().getFileTypeByFileName(filePath.name)
                val baseContent = DiffContentFactory.getInstance().create(project, baseText, fileType)
                val headContent = change.virtualFile
                    ?.let { DiffContentFactory.getInstance().create(project, it) }
                    ?: DiffContentFactory.getInstance().create("")
                val request = SimpleDiffRequest("Diff: $relativePath", baseContent, headContent, "base", "HEAD")
                if (scrollTarget is NavTarget.Line) {
                    request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, com.intellij.openapi.util.Pair.create(Side.RIGHT, scrollTarget.line))
                }
                request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(ActionManager.getInstance().getAction("Preflight.ToggleReviewed")))
                val scrollPolicy = when (scrollTarget) {
                    is NavTarget.FirstChange -> DiffUserDataKeysEx.ScrollToPolicy.FIRST_CHANGE
                    is NavTarget.LastChange -> DiffUserDataKeysEx.ScrollToPolicy.LAST_CHANGE
                    else -> null
                }
                diffProcessor.setRequest(request, scrollPolicy) {
                    val editor = currentEditor()
                    // SCROLL_TO_LINE only scrolls the view; place the caret too, since the next
                    // navigation step starts from the caret line.
                    if (editor != null && scrollTarget is NavTarget.Line) moveCaretTo(editor, scrollTarget.line)
                    if (editor != null) {
                        gutterHandler.attach(editor, relativePath, disposable)
                        currentInlayManager?.let { Disposer.dispose(it) }
                        currentInlayManager = CommentInlayManager(editor, commentStore, relativePath, disposable)
                        project.service<MainEditorCommentHandler>().registerExternalEditor(editor)
                    }
                }
                currentFile = relativePath
                fileTree.repaint()
                DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, true)
            }
        }
    }

    private fun currentEditor(): EditorEx? = if (diffProcessor.isDisposed) null else diffProcessor.editor2()

    override fun navigate(kind: NavKind, direction: NavDirection) {
        val leaves = changeLeaves()
        if (leaves.isEmpty()) return
        val files = leaves.map { it.first }
        val editor = currentEditor()
        val file = currentFile.takeIf { editor != null }
        val line = editor?.caretModel?.logicalPosition?.line ?: -1
        val target = when (kind) {
            NavKind.COMMENT -> ReviewNavigation.nextComment(
                files, { commentPositions(it, editor) }, file, line, direction, lastCommentJump?.takeIf { file != null }
            )
            NavKind.CHANGE -> ReviewNavigation.nextChange(files, currentChangeLines(), file, line, direction)
        } ?: return
        lastCommentJump = (target as? NavTarget.Line)?.commentId
        if (target.file == file && editor != null) {
            when (target) {
                is NavTarget.Line -> moveCaretTo(editor, target.line)
                is NavTarget.FirstChange -> currentDiffViewer()?.scrollToChange(DiffUserDataKeysEx.ScrollToPolicy.FIRST_CHANGE)
                is NavTarget.LastChange -> currentDiffViewer()?.scrollToChange(DiffUserDataKeysEx.ScrollToPolicy.LAST_CHANGE)
            }
            DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, true)
            return
        }
        val node = leaves.first { it.first == target.file }.second
        val path = TreePath(node.path)
        fileTree.scrollPathToVisible(path)
        if (fileTree.selectionPath == path) {
            openDiff(node.userObject as Change, target)
        } else {
            pendingScrollTarget = target
            fileTree.selectionPath = path
        }
    }

    /** Relative path and tree node of every changed file, in the order the tree shows them. */
    private fun changeLeaves(): List<Pair<String, DefaultMutableTreeNode>> {
        val repoRoot = currentRepo?.root?.path
        return treeRoot.depthFirstEnumeration().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .mapNotNull { node ->
                val change = node.userObject as? Change ?: return@mapNotNull null
                val path = (change.afterRevision?.file ?: change.beforeRevision?.file)?.path ?: return@mapNotNull null
                val relative = if (repoRoot != null) path.removePrefix("$repoRoot/") else path
                relative to node
            }
            .toList()
    }

    private fun commentPositions(file: String, editor: EditorEx?): List<CommentPosition> {
        val registry = project.service<CommentAnchorRegistry>()
        return commentStore.getCommentsForFile(file)
            .filter { !it.resolved && !registry.isOrphaned(it.id) }
            .map { comment ->
                // The open file's anchors track edits made since the last flush to comments.json.
                val live = if (file == currentFile && editor != null) registry.currentRange(editor.document, comment.id) else null
                CommentPosition(live?.first ?: comment.startLine ?: comment.line, comment.id)
            }
    }

    override fun toggleReviewed() {
        currentFile?.let { toggleReviewed(it) }
    }

    override fun currentFileReviewState(): Boolean? = currentFile?.let { it in reviewedFiles }

    private fun reviewCheckboxFileAt(e: MouseEvent): String? {
        val path = fileTree.getPathForLocation(e.x, e.y) ?: return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        if (node.userObject !is Change) return null
        val bounds = fileTree.getPathBounds(path) ?: return null
        if (e.x > bounds.x + ReviewCheckboxIcon(false).iconWidth + JBUI.scale(2)) return null
        return changeLeaves().firstOrNull { it.second === node }?.first
    }

    private fun toggleReviewed(file: String) {
        // The mark hashes the file on disk, so unsaved edits have to be on disk first.
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath("$it/$file") }
            ?.let { vf -> FileDocumentManager.getInstance().getCachedDocument(vf) }
            ?.let { FileDocumentManager.getInstance().saveDocument(it) }
        val commit = currentRepo?.currentRevision
        val wasReviewed = file in reviewedFiles
        // Show the new state right away; recomputeReviewed() confirms it from disk.
        reviewedFiles = if (wasReviewed) reviewedFiles - file else reviewedFiles + file
        fileTree.repaint()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (wasReviewed) reviewedStore.unmark(file) else reviewedStore.markReviewed(file, commit)
            recomputeReviewed()
        }
    }

    private fun recomputeReviewed() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val reviewed = reviewedStore.reviewedAmong(changedFiles)
            ApplicationManager.getApplication().invokeLater {
                reviewedFiles = reviewed
                fileTree.repaint()
            }
        }
    }

    private fun moveCaretTo(editor: EditorEx, line: Int) {
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line.coerceIn(0, maxOf(0, editor.document.lineCount - 1)), 0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun currentDiffViewer(): SimpleDiffViewer? =
        if (diffProcessor.isDisposed) null else diffProcessor.activeViewer as? SimpleDiffViewer

    private fun currentChangeLines(): List<Int> =
        currentDiffViewer()?.diffChanges?.map { it.getStartLine(Side.RIGHT) }.orEmpty()

    private fun onCommentAdded() {
        val editor = currentEditor() ?: return
        val file = currentFile ?: return
        gutterHandler.attach(editor, file, disposable)
        currentInlayManager?.refresh()
        project.service<MainEditorCommentHandler>().refreshFile(file)
        refresh()
    }

    private fun reloadComments() {
        project.service<MainEditorCommentHandler>().refreshAllInlays()
        currentInlayManager?.refresh()
        refresh()
    }

    private fun collapseAllComments() {
        currentInlayManager?.collapseAll()
        project.service<MainEditorCommentHandler>().collapseAllComments()
    }

    private fun deleteAllComments() {
        val result = Messages.showYesNoDialog(
            project,
            "Delete all ${commentStore.getComments().size} comments? This cannot be undone.",
            "Delete All Comments",
            Messages.getWarningIcon()
        )
        if (result != Messages.YES) return
        commentStore.removeAllComments()
        reloadComments()
    }

    private fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val repo = service.getRepository()
            val (remote, local) = service.getAllBranches()
            val commits = service.getCommitsSinceMain()
            val diffBaseBeforeRefresh = selectedDiffBase

            val effectiveDiffBase = when (diffBaseBeforeRefresh) {
                is DiffBase.Branch -> {
                    val allBranches = local + remote
                    val name = allBranches.firstOrNull { it == diffBaseBeforeRefresh.name }
                        ?: local.firstOrNull() ?: remote.firstOrNull() ?: diffBaseBeforeRefresh.name
                    DiffBase.Branch(name)
                }
                is DiffBase.Commit -> {
                    val isReachable = repo != null && service.isAncestorOfHead(diffBaseBeforeRefresh.sha)
                    selectEffectiveDiffBase(diffBaseBeforeRefresh, isReachable, DiffBase.Branch(lastActiveBaseBranch))
                }
            }
            // A Base Commit can only fall back to a Base Branch, never the other way round —
            // see ADR 0003.
            val fallbackTriggered = diffBaseBeforeRefresh is DiffBase.Commit && effectiveDiffBase is DiffBase.Branch

            val revision = when (effectiveDiffBase) {
                is DiffBase.Branch -> if (repo != null) service.getMergeBase(effectiveDiffBase.name) else null
                is DiffBase.Commit -> effectiveDiffBase.sha
            }
            // getChangedFiles always includes Uncommitted Changes, even without a resolvable
            // revision (see BranchDiffService.getChangedFiles).
            val changes = service.getChangedFiles(effectiveDiffBase)

            ApplicationManager.getApplication().invokeLater {
                currentRemoteBranches = remote
                currentLocalBranches = local
                currentCommits = commits
                selectedDiffBase = effectiveDiffBase
                if (effectiveDiffBase is DiffBase.Branch) lastActiveBaseBranch = effectiveDiffBase.name
                updateDiffBaseButton(diffBaseButton, effectiveDiffBase)
                currentRepo = repo
                currentRevision = revision
                rebuildTree(repo?.root?.path, changes)
                selectCurrentFileInTree()
                val repoRoot = repo?.root?.path
                val changedFilePaths = changes.mapNotNull { change ->
                    val path = change.afterRevision?.file?.path
                        ?: change.beforeRevision?.file?.path
                    if (repoRoot != null && path != null) path.removePrefix("$repoRoot/") else path
                }.toSet()
                changedFiles = changedFilePaths
                recomputeReviewed()
                val orphanedIds = project.service<CommentAnchorRegistry>().orphanedIds()
                outdatedPanel.refresh(commentStore.getStaleComments(changedFilePaths, orphanedIds))
                project.service<ReviewNavigationService>().register(this, disposable)
                if (fallbackTriggered) notifyBaseCommitFallback(effectiveDiffBase)
            }
        }
    }

    private fun notifyBaseCommitFallback(fallback: DiffBase) {
        val branchName = (fallback as? DiffBase.Branch)?.name ?: return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Preflight")
            .createNotification(
                "Base Commit nicht mehr erreichbar",
                "Zurückgesetzt auf Base Branch \"$branchName\".",
                NotificationType.WARNING
            )
            .notify(project)
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

    /** treeModel.reload() drops the selection; put it back on the file shown in the diff. */
    private fun selectCurrentFileInTree() {
        val node = changeLeaves().firstOrNull { it.first == currentFile }?.second ?: return
        val path = TreePath(node.path)
        restoringSelection = true
        try {
            fileTree.selectionPath = path
        } finally {
            restoringSelection = false
        }
        fileTree.scrollPathToVisible(path)
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

        fun setRequest(
            request: DiffRequest,
            scrollToPolicy: DiffUserDataKeysEx.ScrollToPolicy? = null,
            onApplied: (() -> Unit)? = null
        ) {
            this.onApplied = onApplied
            pendingRequest = request
            ApplicationManager.getApplication().invokeLater { updateRequest(false, scrollToPolicy) }
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

private class CommitTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: javax.swing.JTree, value: Any?, selected: Boolean,
        expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val obj = node.userObject) {
            is CommitLeaf -> {
                append(
                    formatDiffBaseLabel(DiffBase.Commit(obj.sha, obj.subject)),
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                )
                append("  ${formatCommitTimestamp(obj.commitEpochSeconds)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is String -> append(obj, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}

private class FileChangeTreeCellRenderer(
    private val commentStore: CommentStore,
    private val basePath: String,
    private val isShownInDiff: (String) -> Boolean,
    private val isReviewed: (String) -> Boolean
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
                val relPath = filePath.path.removePrefix("$basePath/")
                val current = isShownInDiff(relPath)
                val reviewed = isReviewed(relPath)
                icon = com.intellij.ui.RowIcon(ReviewCheckboxIcon(reviewed), fileType.icon)
                val status = if (reviewed) SimpleTextAttributes.GRAYED_ATTRIBUTES else fileStatusAttributes(obj, filePath.path)
                append(name, if (current) status.derive(SimpleTextAttributes.STYLE_BOLD, null, null, null) else status)
                if (obj.type == Change.Type.MOVED) {
                    val oldRelPath = obj.beforeRevision?.file?.path?.removePrefix("$basePath/")
                    if (oldRelPath != null) {
                        append("  ← $oldRelPath", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
                if (!selected && filePath.path.contains("/test/")) {
                    background = JBColor(Color(0xC8E6C9), Color(0x2D4A2D))
                    isOpaque = true
                } else {
                    isOpaque = false
                }
                val unresolved = commentStore.getCommentsForFile(relPath).count { !it.resolved }
                if (unresolved > 0) {
                    append("  ● $unresolved", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.ORANGE))
                }
                if (current) {
                    append("  ◀", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBUI.CurrentTheme.Link.Foreground.ENABLED))
                }
            }
            is String -> {
                icon = AllIcons.Nodes.Folder
                append(obj, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val count = leafCount(node)
                append("  $count ${if (count == 1) "file" else "files"}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val reviewedCount = reviewedLeafCount(node)
                if (reviewedCount > 0) {
                    append("  ✓ $reviewedCount/$count", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, REVIEWED_COLOR))
                }
            }
        }
    }

    private fun reviewedLeafCount(node: DefaultMutableTreeNode): Int =
        node.depthFirstEnumeration().asSequence()
            .mapNotNull { ((it as? DefaultMutableTreeNode)?.userObject as? Change) }
            .count { change ->
                val path = (change.afterRevision?.file ?: change.beforeRevision?.file)?.path ?: return@count false
                isReviewed(path.removePrefix("$basePath/"))
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
            Change.Type.MOVED -> SimpleTextAttributes(
                SimpleTextAttributes.STYLE_PLAIN,
                JBColor(Color(0x3B82C4), Color(0x6CA0DC))
            )
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
}

private val REVIEWED_COLOR = JBColor(Color(0x2E7D32), Color(0x81C784))
