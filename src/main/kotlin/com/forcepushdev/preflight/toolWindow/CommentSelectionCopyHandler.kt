package com.forcepushdev.preflight.toolWindow

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/** Copies text selected in a comment card; everything else goes to the editor's own Copy. */
class CommentSelectionCopyHandler(private val original: EditorActionHandler) : EditorActionHandler() {

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val selected = CommentInlayManager.forEditor(editor)?.selectedText()
        if (selected != null) {
            CopyPasteManager.getInstance().setContents(StringSelection(selected))
        } else {
            original.execute(editor, caret, dataContext)
        }
    }

    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        CommentInlayManager.forEditor(editor)?.selectedText() != null || original.isEnabled(editor, caret, dataContext)
}
