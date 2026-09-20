package com.forcepushdev.preflight.toolWindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

class CommentStateIcon(private val resolved: Boolean, private val expanded: Boolean) : Icon {
    override fun getIconWidth() = SIZE
    override fun getIconHeight() = SIZE

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = if (resolved) JBColor(Color(0x59A869), Color(0x6ABF75)) else JBUI.CurrentTheme.Link.Foreground.ENABLED
        g2.font = g2.font.deriveFont(FONT_SIZE)
        val glyph = if (resolved) "✓" else if (expanded) "▼" else "▶"
        val fm = g2.getFontMetrics(g2.font)
        g2.drawString(glyph, x + (SIZE - fm.stringWidth(glyph)) / 2, y + (SIZE + fm.ascent - fm.descent) / 2)
    }

    companion object {
        private const val SIZE = 12
        private const val FONT_SIZE = 11f
    }
}
