package com.forcepushdev.preflight.toolWindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

class ReviewCheckboxIcon(private val checked: Boolean) : Icon {
    override fun getIconWidth() = JBUI.scale(SIZE + GAP)
    override fun getIconHeight() = JBUI.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val size = JBUI.scale(SIZE)
            val arc = JBUI.scale(3)
            if (checked) {
                g2.color = CHECKED_COLOR
                g2.fillRoundRect(x, y, size, size, arc, arc)
                g2.color = Color.WHITE
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val s = size / 12f
                g2.drawPolyline(
                    intArrayOf(x + (3 * s).toInt(), x + (5 * s).toInt(), x + (9 * s).toInt()),
                    intArrayOf(y + (6 * s).toInt(), y + (8.5 * s).toInt(), y + (3.5 * s).toInt()),
                    3
                )
            } else {
                g2.color = BORDER_COLOR
                g2.drawRoundRect(x, y, size - 1, size - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val SIZE = 12
        private const val GAP = 4
        private val CHECKED_COLOR = JBColor(Color(0x2E7D32), Color(0x5FA463))
        private val BORDER_COLOR = JBColor(Color(0x9AA0A6), Color(0x6F737A))
    }
}
