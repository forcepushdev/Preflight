package com.forcepushdev.preflight.toolWindow

import com.forcepushdev.preflight.services.DiffBase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MAX_SUBJECT_LENGTH = 40
private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Short label for the "Base:" button — long commit subjects are truncated so the button never
 * outgrows the tool window (see the panel-overflow bug report). Use [formatDiffBaseTooltip] to
 * show the untruncated text on hover. */
fun formatDiffBaseLabel(diffBase: DiffBase): String = when (diffBase) {
    is DiffBase.Branch -> diffBase.name
    is DiffBase.Commit -> "${diffBase.sha.take(7)} — ${truncateSubject(diffBase.subject)}"
}

fun formatDiffBaseTooltip(diffBase: DiffBase): String = when (diffBase) {
    is DiffBase.Branch -> diffBase.name
    is DiffBase.Commit -> "${diffBase.sha.take(7)} — ${diffBase.subject}"
}

fun formatCommitTimestamp(epochSeconds: Long): String =
    TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

internal fun truncateSubject(subject: String, maxLength: Int = MAX_SUBJECT_LENGTH): String =
    if (subject.length <= maxLength) subject else subject.take(maxLength - 1).trimEnd() + "…"

fun selectEffectiveDiffBase(current: DiffBase, isReachable: Boolean, fallback: DiffBase.Branch): DiffBase =
    if (current is DiffBase.Commit && !isReachable) fallback else current
