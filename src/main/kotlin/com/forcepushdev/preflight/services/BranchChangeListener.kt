package com.forcepushdev.preflight.services

import com.forcepushdev.preflight.services.CommentStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

class BranchChangeListener(private val project: Project) : GitRepositoryChangeListener {

    override fun repositoryChanged(repository: GitRepository) {
        ApplicationManager.getApplication().invokeLater {
            project.service<CommentStore>().reload()
            project.service<MainEditorCommentHandler>().refreshAllInlays()
        }
    }
}
