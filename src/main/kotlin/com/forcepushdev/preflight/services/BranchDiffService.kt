package com.forcepushdev.preflight.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.changes.GitChangeUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

@Service(Service.Level.PROJECT)
class BranchDiffService(private val project: Project) {

    fun getRepository(): GitRepository? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()

    fun getMainBranch(): String {
        val repo = getRepository() ?: return "main"
        val remoteNames = repo.branches.remoteBranches.map { it.name }
        return detectMainBranch(remoteNames)
    }

    fun getAllBranches(): Pair<List<String>, List<String>> {
        val repo = getRepository() ?: return Pair(emptyList(), emptyList())
        val remote = repo.branches.remoteBranches.map { it.name }
        val local = repo.branches.localBranches.map { it.name }
        return Pair(remote, local)
    }

    fun getMergeBase(baseBranch: String = getMainBranch()): String? {
        val repo = getRepository() ?: return null
        val handler = GitLineHandler(project, repo.root, GitCommand.MERGE_BASE)
        handler.addParameters(baseBranch, "HEAD")
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) parseMergeBase(result.output.joinToString("\n")) else null
    }

    fun getChangedFiles(baseBranch: String = getMainBranch()): List<Change> {
        val repo = getRepository() ?: return emptyList()
        val mergeBase = getMergeBase(baseBranch) ?: return emptyList()
        return GitChangeUtils.getDiff(repo, mergeBase, "HEAD", false)?.toList() ?: emptyList()
    }

    fun getFileContentAtRevision(path: String, revision: String): String {
        val repo = getRepository() ?: return ""
        val handler = GitLineHandler(project, repo.root, GitCommand.SHOW)
        handler.addParameters("$revision:$path")
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.output.joinToString("\n") else ""
    }

    internal fun detectMainBranch(branchNames: List<String>): String =
        branchNames.firstOrNull { it == "main" || it.endsWith("/main") }
            ?: branchNames.firstOrNull { it == "master" || it.endsWith("/master") }
            ?: "main"

    internal fun parseMergeBase(output: String): String? = output.trim().takeIf { it.isNotEmpty() }
}
