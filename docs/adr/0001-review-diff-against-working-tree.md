# Review Diff compares against the working tree, not HEAD

Preflight originally computed the Review Diff as `mergeBase..HEAD` — committed changes only, which is why you had to commit before Preflight would show a file at all. To support commenting on Uncommitted Changes, we changed this to `mergeBase..<working tree>`, merging committed and uncommitted changes into a single tree instead of showing them as separate sections.

**Considered options**: keep two separate diffs (a "Committed" section for `mergeBase..HEAD` and a "Working Tree" section for `HEAD..working tree`), which preserves the distinction between what's committed and what isn't. Rejected in favor of one merged tree for simplicity — you review "what's different from Base," full stop, without needing to mentally merge two lists yourself.

**Consequences**: the diff viewer's "current" side is always the live, editable on-disk file — not a frozen git blob — so typing there edits the real file, same as any other editor. The tree no longer visually distinguishes committed from uncommitted files; if that distinction turns out to matter in practice, revisit this before building more on top of it.
