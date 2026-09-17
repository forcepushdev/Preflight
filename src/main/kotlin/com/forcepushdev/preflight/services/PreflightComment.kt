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
    val id: String = UUID.randomUUID().toString()
)
