package com.forcepushdev.preflight.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.changes.GitChangeUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File

@Service(Service.Level.PROJECT)
class BranchDiffService(private val project: Project) {

    fun getRepository(): GitRepository? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()

    fun getMainBranch(): String {
        val repo = getRepository() ?: return "main"
        val remoteNames = repo.branches.remoteBranches.map { it.name }
        val localNames = repo.branches.localBranches.map { it.name }
        return detectMainBranch(readRemoteHeads(repo), remoteNames, localNames)
    }

    /**
     * The default branch each remote advertises (`refs/remotes/<remote>/HEAD`, set by clone or
     * `git remote set-head`), e.g. "origin/develop". Read from disk rather than via git, since the
     * default branch is needed on the EDT when the tool window opens.
     */
    private fun readRemoteHeads(repo: GitRepository): List<String> {
        val remotesDir = File(repo.repositoryFiles.refsHeadsFile.parentFile, "remotes")
        return repo.remotes.map { it.name }.mapNotNull { remote ->
            val headFile = File(remotesDir, "$remote/HEAD").takeIf { it.isFile } ?: return@mapNotNull null
            headFile.readText().trim().removePrefix("ref: refs/remotes/").takeIf { it.startsWith("$remote/") }
        }
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
        val mergeBase = getMergeBase(baseBranch)
        // Uncommitted Changes are shown even without a resolvable merge-base (e.g. a repo with no
        // history yet) — they don't depend on the Base Branch, only on the working tree vs. HEAD.
        return if (mergeBase != null) getChangedFilesAgainstRevision(mergeBase) else mergeChanges(emptyList(), getUncommittedChanges())
    }

    private fun getChangedFilesAgainstRevision(revision: String): List<Change> {
        val repo = getRepository()
        val committed = if (repo != null) {
            GitChangeUtils.getDiff(repo, revision, "HEAD", false)?.toList() ?: emptyList()
        } else emptyList()
        return mergeChanges(committed, getUncommittedChanges())
    }

    fun getUncommittedChanges(): List<Change> {
        val changeListManager = ChangeListManager.getInstance(project)
        val tracked = changeListManager.allChanges.toList()
        val untracked = changeListManager.unversionedFilesPaths.map { filePath ->
            Change(null, CurrentContentRevision(filePath))
        }
        return tracked + untracked
    }

    /**
     * Merges the committed diff (mergeBase..HEAD) with local Uncommitted Changes into a single
     * Review Diff. A path present in both wins on the uncommitted side, since that reflects the
     * newest on-disk state (see ADR 0001).
     */
    internal fun mergeChanges(committed: List<Change>, uncommitted: List<Change>): List<Change> {
        val uncommittedPaths = uncommitted.mapNotNull { changePath(it) }.toSet()
        return committed.filter { changePath(it) !in uncommittedPaths } + uncommitted
    }

    private fun changePath(change: Change): String? =
        (change.afterRevision?.file ?: change.beforeRevision?.file)?.path

    fun getCommitsSinceMain(): List<CommitInfo> {
        val repo = getRepository() ?: return emptyList()
        val mergeBase = getMergeBase(getMainBranch()) ?: return emptyList()
        val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
        handler.addParameters("--pretty=format:%H%x00%s%x00%ct", "$mergeBase..HEAD")
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) parseCommitLog(result.output.joinToString("\n")) else emptyList()
    }

    fun isAncestorOfHead(sha: String): Boolean {
        val repo = getRepository() ?: return false
        val handler = GitLineHandler(project, repo.root, GitCommand.MERGE_BASE)
        handler.addParameters("--is-ancestor", sha, "HEAD")
        return Git.getInstance().runCommand(handler).success()
    }

    fun getChangedFiles(diffBase: DiffBase): List<Change> = when (diffBase) {
        is DiffBase.Branch -> getChangedFiles(diffBase.name)
        is DiffBase.Commit -> getChangedFilesAgainstRevision(diffBase.sha)
    }

    fun getFileContentAtRevision(path: String, revision: String): String {
        val repo = getRepository() ?: return ""
        val handler = GitLineHandler(project, repo.root, GitCommand.SHOW)
        handler.addParameters("$revision:$path")
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.output.joinToString("\n") else ""
    }

    /**
     * The remote's advertised default branch wins (origin first). Otherwise a conventional name is
     * looked up exactly as `<remote>/<name>` or `<name>` — never by suffix, so an old
     * "feature/main" can't be mistaken for the default branch.
     */
    internal fun detectMainBranch(remoteHeads: List<String>, remoteBranches: List<String>, localBranches: List<String> = emptyList()): String {
        val all = remoteBranches + localBranches
        remoteHeads.sortedBy { if (it.startsWith("origin/")) 0 else 1 }
            .firstOrNull { it in all }
            ?.let { return it }
        val remotes = remoteBranches.map { it.substringBefore("/") }.distinct().sortedBy { if (it == "origin") 0 else 1 }
        for (name in listOf("main", "master", "develop")) {
            remotes.map { "$it/$name" }.firstOrNull { it in remoteBranches }?.let { return it }
            if (name in localBranches) return name
        }
        return "main"
    }

    internal fun parseMergeBase(output: String): String? = output.trim().takeIf { it.isNotEmpty() }

    internal fun parseCommitLog(output: String): List<CommitInfo> =
        output.split("\n")
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("\u0000", limit = 3)
                CommitInfo(
                    sha = parts[0],
                    subject = parts.getOrElse(1) { "" },
                    commitEpochSeconds = parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0L
                )
            }
}
