package com.forcepushdev.preflight.services

import java.util.UUID

data class Reply(
    val text: String,
    val author: String = "user"
)

data class PreflightComment(
    val file: String,
    val line: Int,
    val comment: String,
    val resolved: Boolean = false,
    val replies: List<Reply> = emptyList(),
    val startLine: Int? = null,
    val author: String? = "user",
    val id: String = UUID.randomUUID().toString(),
    // Text of the commented lines (startLine..line). Lets the comment be re-found by content when
    // line numbers go stale, e.g. after edits made without IntelliJ.
    val anchorText: String? = null
)
