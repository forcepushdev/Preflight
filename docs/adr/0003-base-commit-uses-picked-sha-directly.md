# Base Commit uses the picked SHA directly, with fallback on rebase

A Base Commit is picked from the current branch's own history (commits since it diverged from main), so it's always an ancestor of HEAD by construction — recomputing `mergeBase(commit, HEAD)` would always just return the commit itself. We use the picked SHA directly as the Review Diff's starting point instead, unlike a Base Branch, which always goes through merge-base. The Base Commit selection is pinned by SHA and stays fixed as the current branch gains new commits, growing the Review Diff rather than resetting.

**Considered options**: recompute merge-base against the picked commit on every refresh anyway, for defensive consistency with the Base Branch path and to guard against the commit becoming unreachable (rebase, amend, squash) between selection and refresh. Rejected for the common case — it's a pointless computation when the invariant holds — but the underlying risk it would have caught is real.

**Consequences**: if the pinned SHA becomes unreachable from HEAD (rebase, amend, squash rewrote it away), resolving it (e.g. `git show <sha>:<path>`) fails. Preflight must detect this on refresh, fall back to the last-active Base Branch, and notify the user — this fallback path is required, not optional, precisely because we skipped the defensive merge-base recomputation.
