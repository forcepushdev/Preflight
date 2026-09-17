package com.forcepushdev.preflight.services

/**
 * The starting point of the Review Diff — either a Base Branch or a Base Commit.
 * See CONTEXT.md and docs/adr/0003-base-commit-uses-picked-sha-directly.md.
 */
sealed class DiffBase {
    data class Branch(val name: String) : DiffBase()
    data class Commit(val sha: String, val subject: String) : DiffBase()
}

data class CommitInfo(val sha: String, val subject: String, val commitEpochSeconds: Long)
