package com.forcepushdev.preflight.toolWindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager

interface ReviewNavigable {
    fun navigate(kind: NavKind, direction: NavDirection)
}

/**
 * Hands navigation actions to the Preflight panel. The panel only exists once its tool window was
 * opened and only knows the file order after its first refresh, so a request made before that is
 * parked and replayed when the panel registers.
 */
@Service(Service.Level.PROJECT)
class ReviewNavigationService(private val project: Project) {

    private var target: ReviewNavigable? = null
    private var pending: Pair<NavKind, NavDirection>? = null

    fun register(navigable: ReviewNavigable, disposable: Disposable) {
        if (target !== navigable) {
            target = navigable
            Disposer.register(disposable) { if (target === navigable) target = null }
        }
        pending?.let { (kind, direction) ->
            pending = null
            navigable.navigate(kind, direction)
        }
    }

    fun navigate(kind: NavKind, direction: NavDirection) {
        target?.let { return it.navigate(kind, direction) }
        pending = kind to direction
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
