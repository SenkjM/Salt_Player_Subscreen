package com.senkj.spwsidescreen

import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 初始显示的控制小窗：可拖动到目标显示器，点“全屏”即在该显示器上铺满。
 */
class ControlPanel(private val onFullscreen: () -> Unit) : JPanel() {

    private val button = FlatButton("全屏")
    private var pressPoint: Point? = null

    init {
        // 直角窗口：整块面板铺满深色背景，避免圆角外露出黑边。
        isOpaque = true
        background = Color(20, 20, 26)
        layout = null
        button.isFocusable = false
        button.isFocusPainted = false
        button.font = Font("Microsoft YaHei UI", Font.BOLD, 13)
        button.addActionListener { onFullscreen() }
        add(button)

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                pressPoint = e.point
            }

            override fun mouseReleased(e: MouseEvent) {
                pressPoint = null
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) onFullscreen()
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val start = pressPoint ?: return
                val window = SwingUtilities.getWindowAncestor(this@ControlPanel) ?: return
                window.setLocation(e.xOnScreen - start.x, e.yOnScreen - start.y)
            }
        })
    }

    override fun doLayout() {
        super.doLayout()
        val size = button.preferredSize
        button.setBounds(width - size.width - 16, (height - size.height) / 2, size.width, size.height)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            // 直角窗口：整块铺满深色，只画一圈很淡的直角描边。
            g2.color = Color(20, 20, 26)
            g2.fillRect(0, 0, w, h)
            g2.color = Color(255, 255, 255, 30)
            g2.drawRect(0, 0, w - 1, h - 1)

            val coverSize = (h - 24).coerceAtLeast(40)
            val coverX = 12
            val coverY = (h - coverSize) / 2
            paintCover(g2, coverX, coverY, coverSize)

            val textLeft = coverX + coverSize + 14
            val textWidth = (w - textLeft - 106).coerceAtLeast(40)

            val title = NowPlayingState.media?.title?.takeIf { it.isNotBlank() } ?: "等待播放"
            g2.font = Font("Microsoft YaHei UI", Font.BOLD, 14)
            g2.color = Color(255, 255, 255, 235)
            g2.drawString(ellipsize(g2, title, textWidth), textLeft, (h / 2f - 2).roundToInt())

            g2.font = Font("Microsoft YaHei UI", Font.PLAIN, 11)
            g2.color = Color(255, 255, 255, 120)
            g2.drawString("拖到副屏后点“全屏”", textLeft, (h / 2f + 16).roundToInt())
        } finally {
            g2.dispose()
        }
    }

    private fun paintCover(g2: Graphics2D, x: Int, y: Int, size: Int) {
        val radius = (size * 0.16f).roundToInt().coerceAtLeast(6)
        val shape = RoundRectangle2D.Float(
            x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat(), radius.toFloat(), radius.toFloat()
        )
        val oldClip = g2.clip
        g2.clip = shape
        val image = NowPlayingState.cover
        if (image != null) {
            drawCenterCrop(g2, image, x, y, size)
        } else {
            g2.color = Color(255, 255, 255, 26)
            g2.fillRect(x, y, size, size)
            // 占位封面用图形绘制，避免字体缺字出现方框。
            g2.color = Color(255, 255, 255, 46)
            val circle = (size * 0.46f).roundToInt().coerceAtLeast(12)
            g2.fillOval(x + (size - circle) / 2, y + (size - circle) / 2, circle, circle)
            g2.color = Color(255, 255, 255, 170)
            val tri = Path2D.Float()
            val cx = x + size / 2.0
            val cy = y + size / 2.0
            val r = circle * 0.22
            tri.moveTo(cx - r * 0.7, cy - r)
            tri.lineTo(cx + r, cy)
            tri.lineTo(cx - r * 0.7, cy + r)
            tri.closePath()
            g2.fill(tri)
        }
        g2.clip = oldClip
    }

    private fun drawCenterCrop(g2: Graphics2D, image: BufferedImage, x: Int, y: Int, size: Int) {
        val scale = max(size.toFloat() / image.width, size.toFloat() / image.height)
        val dw = (image.width * scale).roundToInt()
        val dh = (image.height * scale).roundToInt()
        g2.drawImage(image, x + (size - dw) / 2, y + (size - dh) / 2, dw, dh, null)
    }

    private fun ellipsize(g2: Graphics2D, text: String, maxWidth: Int): String {
        val fm = g2.fontMetrics
        if (fm.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && fm.stringWidth(text.substring(0, end) + "…") > maxWidth) end--
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }
}

/** 自绘的扁平按钮：避免不同 L&F 下按钮背景/文字颜色被覆盖导致看不见。 */
private class FlatButton(text: String) : JButton(text) {

    init {
        isFocusable = false
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        font = Font("Microsoft YaHei UI", Font.BOLD, 13)
        foreground = Color.WHITE
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = BorderFactory.createEmptyBorder(6, 18, 6, 18)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = if (model.isPressed) Color(0x6A58E8) else Color(0x7C6CF0)
            g2.fill(RoundRectangle2D.Float(0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), 14f, 14f))
            g2.font = font
            g2.color = Color.WHITE
            val fm = g2.fontMetrics
            val tw = fm.stringWidth(text)
            g2.drawString(text, (width - tw) / 2, (height - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }
}
