package com.forcepushdev.preflight.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.forcepushdev.preflight.services.CommentMarkdownExporter
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem

interface ReviewController {
    fun navigate(kind: NavKind, direction: NavDirection)
    fun toggleReviewed()

    /** Whether the file shown in the diff is reviewed; null when no file is shown. */
    fun currentFileReviewState(): Boolean?
}

/**
 * Hands navigation and review actions to the Preflight panel. The panel only exists once its tool window was
 * opened and only knows the file order after its first refresh, so a request made before that is
 * parked and replayed when the panel registers.
 */
@Service(Service.Level.PROJECT)
class ReviewNavigationService(private val project: Project) {

    private var target: ReviewController? = null
    private var pending: ((ReviewController) -> Unit)? = null

    fun register(navigable: ReviewController, disposable: Disposable) {
        if (target !== navigable) {
            target = navigable
            Disposer.register(disposable) { if (target === navigable) target = null }
        }
        pending?.let {
            pending = null
            it(navigable)
        }
    }

    fun navigate(kind: NavKind, direction: NavDirection) = withController { it.navigate(kind, direction) }

    fun toggleReviewed() {
        target?.toggleReviewed()
    }

    fun currentFileReviewState(): Boolean? = target?.currentFileReviewState()

    private fun withController(call: (ReviewController) -> Unit) {
        target?.let { return call(it) }
        pending = call
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    companion object {
        const val TOOL_WINDOW_ID = "Preflight"
    }
}

abstract class ReviewNavigationAction(
    private val kind: NavKind,
    private val direction: NavDirection
) : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<ReviewNavigationService>()?.navigate(kind, direction)
    }
}

class NextCommentAction : ReviewNavigationAction(NavKind.COMMENT, NavDirection.NEXT)
class PreviousCommentAction : ReviewNavigationAction(NavKind.COMMENT, NavDirection.PREVIOUS)
class NextChangeAction : ReviewNavigationAction(NavKind.CHANGE, NavDirection.NEXT)
class PreviousChangeAction : ReviewNavigationAction(NavKind.CHANGE, NavDirection.PREVIOUS)

const val PREFLIGHT_TOOLBAR_PLACE = "Preflight"

/** Marks the file shown in the Preflight diff as reviewed, or clears the mark. */
class ToggleReviewedAction : DumbAwareToggleAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.service<ReviewNavigationService>()?.currentFileReviewState() == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.service<ReviewNavigationService>()?.toggleReviewed()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val state = e.project?.service<ReviewNavigationService>()?.currentFileReviewState()
        e.presentation.isEnabled = state != null
        e.presentation.text = if (state == true) "Reviewed" else "Mark as Reviewed"
        // Labelled in the diff toolbar; in the Preflight tool window toolbar the icon plus tooltip is enough.
        e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, e.place != PREFLIGHT_TOOLBAR_PLACE)
    }
}

/** Writes the open comments to `.preflight/comments.md` and opens it, to read them before handing them to the agent. */
class OpenCommentsMarkdownAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = project.service<CommentMarkdownExporter>().export() ?: return
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return
        OpenFileDescriptor(project, virtualFile).navigate(true)
    }
}
