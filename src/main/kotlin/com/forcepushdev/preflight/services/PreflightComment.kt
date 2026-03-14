package com.forcepushdev.preflight.services

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
    val branch: String = "",
    val author: String? = "user"
)
