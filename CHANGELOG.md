<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Preflight Changelog

## [Unreleased]
### Added
- Inline `code` and fenced ``` code blocks in comments and replies are rendered as code; line breaks in comments are kept.
- Comment text can be selected with the mouse (double-click: word, triple-click: whole comment or reply) and copied with Copy.

### Changed
- A comment whose lines were deleted or rewritten (e.g. by an agent fixing it) moves to the line where they were instead of becoming outdated. Only comments whose file no longer exists are outdated.
- A comment whose stored line lies past the end of the file and whose text cannot be found is shown on the last line.
- The outdated comments panel can be resized.

## [0.0.4] - 2026-11-06
### Added
- Plugin icon (light and dark) and a Preflight icon for the tool window.
- Replies can be deleted.

### Changed
- Comments are shown as cards: a header with author and line range (`You · Lines 3–4`), each reply in its own indented block with its own Edit/Delete/Copy, and buttons for the whole comment (Reply, Edit, Delete, Resolve/Reopen) below a separator. Agent replies are marked `✦ Agent`.
- Commented lines are highlighted in blue with an accent bar on the left instead of yellow.
- Replaced the deprecated `FileEditorManager.openFile(VirtualFile, boolean)` and the experimental `WriteIntentReadAction` with stable platform API.

## [0.0.3] - 2026-11-05
### Added
- Review navigation: next/previous comment and next/previous change, continuing into the next/previous file of the Preflight file tree and wrapping around at the end. Resolved comments are skipped; comments on the same line are visited one by one. Available as actions (`Preflight.NextChange`, `Preflight.PreviousChange`, `Preflight.NextComment`, `Preflight.PreviousComment`) with remappable shortcuts, usable from IdeaVim.
- Mark files as reviewed via a checkbox in the file tree, a toggle in the diff toolbar, the `Preflight.ToggleReviewed` action or the tree context menu. Marks are stored in `.preflight/reviewed.json` with a content hash and the HEAD commit, and survive new commits as long as the file's content is unchanged. Folders show review progress (`✓ 2/5`).
- Markdown export of open comments to `.preflight/comments.md` (path and lines, commented code, author, replies, separators). Once created, the file is rewritten whenever `comments.json` changes.
- Search field in the base branch popup; Enter picks the first match.
- The file shown in the diff is highlighted in the file tree (bold with `◀`).

### Changed
- The default base branch is the remote's advertised default (`origin/HEAD`, e.g. `develop`), then `main`, `master`, `develop` by exact name.

### Fixed
- A branch whose name merely ended in `/main` (e.g. an old `feature/main`) was picked as the default base branch.
- Comment action buttons (Reply, Edit, Delete, …) could be drawn below the comment box, over the code.
- The selection in the file tree was lost after a refresh.

## [0.0.2] - 2026-09-20
### Added
- Diff base selection: review against a base branch or a picked base commit; an unreachable base commit falls back to the last base branch.
- Uncommitted changes are part of the review diff, merged with the committed diff since the merge base.
- Comments have stable IDs and follow edits live via anchors, and are re-found by their commented text (`anchorText`) when line numbers go stale, e.g. after edits made outside IntelliJ.
- Collapsible comment inlays with gutter icons showing the comment state.
- The gutter "+" icon is highlighted for the current selection or caret line.
- Reload button for changed files and comments.
- README section with a prompt for handing comments to an agent.

### Removed
- The per-branch comment filter (briefly configurable, default off) in favour of the diff base selection.

## [0.0.1] - 2026-03-14
### Added
- Preflight tool window listing the files changed since the base branch, with a side-by-side diff.
- Comments on lines or blocks, with replies and resolve/reopen, shown as inlays in the Preflight diff and in regular editors.
- Comments stored locally in `.preflight/comments.json`, readable and answerable by AI agents.
- Panel for outdated comments whose file is no longer part of the diff.
- Refresh when the Git repository changes.
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
