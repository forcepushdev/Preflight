package com.forcepushdev.preflight.toolWindow

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

object CommentInputPopup {

    fun show(editor: EditorEx, line: Int, initialText: String = "", onSave: (String) -> Unit) {
        val safeLineIndex = line.coerceIn(0, maxOf(0, editor.document.lineCount - 1))
        val offset = editor.document.getLineStartOffset(safeLineIndex)
        val point = editor.offsetToXY(offset)
        showPopup(
            anchorComponent = editor.contentComponent,
            anchorPoint = Point(point.x, point.y + editor.lineHeight),
            title = "COMMENT",
            saveLabel = "Save ↵",
            initialText = initialText,
            onSave = onSave
        )
    }

    fun showAtPoint(
        anchorComponent: Component,
        anchorPoint: Point,
        title: String = "REPLY",
        saveLabel: String = "Save ↵",
        initialText: String = "",
        onSave: (String) -> Unit
    ) {
        showPopup(anchorComponent, anchorPoint, title, saveLabel, initialText, onSave)
    }

    private fun showPopup(
        anchorComponent: Component,
        anchorPoint: Point,
        title: String,
        saveLabel: String,
        initialText: String,
        onSave: (String) -> Unit
    ) {
        val textArea = createTextArea(initialText)
        val charCounter = JBLabel("${initialText.length}").apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            horizontalAlignment = SwingConstants.RIGHT
            border = JBUI.Borders.empty(2, 4)
            font = font.deriveFont(11f)
        }
        val saveButton = JButton(saveLabel).apply {
            isEnabled = initialText.isNotBlank()
            isFocusPainted = false
            putClientProperty("JButton.buttonType", "default")
        }
        val discardButton = JButton("Discard").apply { isFocusPainted = false }

        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent) {}

            private fun onTextChanged() {
                charCounter.text = "${textArea.text.length}"
                saveButton.isEnabled = textArea.text.isNotBlank()
            }
        })

        val panel = buildPanel(title, textArea, charCounter, discardButton, saveButton)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, textArea)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(true)
            .setResizable(false)
            .createPopup()

        discardButton.addActionListener { popup.cancel() }
        saveButton.addActionListener { onSave(textArea.text.trim()); popup.closeOk(null) }

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) { e.consume(); popup.cancel() }
                if (e.keyCode == KeyEvent.VK_ENTER && e.isMetaDown && saveButton.isEnabled) {
                    onSave(textArea.text.trim()); popup.closeOk(null)
                }
            }
        })

        popup.show(RelativePoint(anchorComponent, anchorPoint))
    }

    private fun createTextArea(initialText: String) = object : JBTextArea(5, 45) {
        private val placeholder = "Add a comment…"

        init {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
            text = initialText
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isEmpty()) {
                val g2 = g as Graphics2D
                g2.color = JBUI.CurrentTheme.Label.disabledForeground()
                g2.font = font
                val fm = g2.fontMetrics
                g2.drawString(placeholder, insets.left + 8, insets.top + fm.ascent + 8)
            }
        }
    }

    private fun buildPanel(
        title: String,
        textArea: JBTextArea,
        charCounter: JBLabel,
        discardButton: JButton,
        saveButton: JButton
    ): JPanel {
        val titleLabel = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.empty(8, 10, 4, 10)
        }

        val scrollPane = JBScrollPane(textArea).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(0, 8),
                JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
            )
        }

        val textAreaWithCounter = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(scrollPane, BorderLayout.CENTER)
            add(charCounter, BorderLayout.SOUTH)
        }

        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(discardButton)
            add(saveButton)
        }

        return JPanel(BorderLayout(0, 4)).apply {
            preferredSize = Dimension(480, 190)
            border = JBUI.Borders.empty(0, 0, 10, 0)
            add(titleLabel, BorderLayout.NORTH)
            add(textAreaWithCounter, BorderLayout.CENTER)
            add(buttonRow, BorderLayout.SOUTH)
        }
    }
}
