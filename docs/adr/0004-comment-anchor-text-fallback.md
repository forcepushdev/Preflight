# Comments carry an `anchorText` so they can be re-found without a live anchor

The live Comment Anchor (ADR 0002) only exists while a document is open in IntelliJ. Edits made while nothing tracks the document — an agent editing files, the IDE being closed — left `line`/`startLine` in `comments.json` stale, and the anchor was then created at the wrong place. Each comment now also stores `anchorText`, the text of its anchored lines. The live anchor stays the source of truth while it exists; `anchorText` is a fallback for everything else.

- `line`/`startLine` remain the primary anchor. Agents are told to move them when they shift code, so a plugin-less workflow still works. When the lines in `comments.json` change externally, the plugin re-anchors from them.
- When a Comment Anchor is created and the text at the stored position no longer matches `anchorText`, the plugin searches the file for it (nearest occurrence to the stored line, whitespace at line edges ignored) and moves the anchor there. If the text is nowhere to be found, the stored line is kept and the comment is **not** orphaned — the commented code was probably rewritten, not deleted. Orphaning stays reserved for the live anchor collapsing (ADR 0002).
- The debounced write-back now persists `startLine` and `anchorText` too. It previously wrote only `line`, which corrupted multi-line ranges on the next load.
- Rendering reads start and end from the live anchor, not from the stored `line - startLine` span, which is stale between an edit and the next write-back. The highlight is a single range highlighter (it grows with lines typed or pasted inside the block), and inlay and gutter icon are redrawn ~250 ms after the last edit.
- The RangeMarker is created with `surviveOnExternalChange`, so an agent rewriting the file while IntelliJ is open is diffed instead of invalidating the anchor.

**Considered options**: (a) only tell agents to adjust line numbers — works, but a single missed update silently misplaces a comment, and IntelliJ can't tell. (b) content hash instead of text — smaller JSON, but an agent can't grep for a hash. (c) context lines above/below — better for repeated lines like `}`, not needed yet; nearest-occurrence covers small shifts.

**Consequences**: `comments.json` gains an optional field; older files are backfilled the first time a comment is opened. Very short or repeated anchor text (`}`) can resolve to the wrong occurrence after a large shift; add context lines if that shows up in practice.
