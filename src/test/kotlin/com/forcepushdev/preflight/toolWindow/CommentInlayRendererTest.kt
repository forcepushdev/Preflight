package com.forcepushdev.preflight.toolWindow

import junit.framework.TestCase

class CommentInlayRendererTest : TestCase() {

    fun testHeaderLabel_singleLine() {
        assertEquals("You · Line 3", CommentInlayRenderer.headerLabel("user", 2, 2, resolved = false))
    }

    fun testHeaderLabel_lineRange_agent_resolved() {
        assertEquals("Agent · Lines 3–4 · Resolved", CommentInlayRenderer.headerLabel("agent", 2, 3, resolved = true))
    }
}
