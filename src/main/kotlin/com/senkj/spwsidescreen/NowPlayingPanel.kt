package com.senkj.spwsidescreen

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Point
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 当前歌词自动折行的最大行数（超出的部分省略号收尾）。 */
private const val MAX_WRAPPED_LINES = 3

/**
 * 副屏播放界面：左侧正方形容器内封面，右侧歌词。
 *
 * 歌词布局：当前行位于高度 1/4 处，按宽度自动折行（最多三行），下方最多 3 行非当前歌词，
 * 非当前行按 AMLL 公式做高斯虚化；640x340 用完整布局，320x170 自动切换紧凑布局。
 */
class NowPlayingPanel : JPanel() {

    /** 全屏时在右上角显示“退出全屏”（鼠标悬停时才显示）。 */
    var showExitButton: Boolean = false
        set(value) {
            field = value
            if (!value) {
                exitHit = null
                hoverExit = false
            }
            repaint()
        }

    var onExitClick: (() -> Unit)? = null

    /** 是否在封面区域显示播放控件（配置项 show_controls）。 */
    var controlsEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                prevHit = null
                playHit = null
                nextHit = null
                seekHit = null
                hoverControl = -1
            }
            repaint()
        }

    /** 竖屏布局：歌词区域在上，封面容器在下；横竖屏切换时由窗口层设置。 */
    var portrait: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            lastActiveIdx = -1
            animOffset = 0f
            animOffsetVel = 0f
            repaint()
        }

    var onPrevious: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onSeek: ((Float) -> Unit)? = null

    private var prevHit: java.awt.Rectangle? = null
    private var playHit: java.awt.Rectangle? = null
    private var nextHit: java.awt.Rectangle? = null
    private var seekHit: java.awt.Rectangle? = null
    private var hoverControl = -1

    private var exitHit: java.awt.Rectangle? = null
    private var hoverExit = false

    private val blurCache = HashMap<String, BufferedImage>()
    private var lastMediaKey: String? = null

    init {
        isOpaque = true
        background = Color(0x07070A)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (showExitButton && exitHit?.contains(e.point) == true) {
                    onExitClick?.invoke()
                    return
                }
                if (!controlsEnabled) return
                when {
                    prevHit?.contains(e.point) == true -> onPrevious?.invoke()
                    playHit?.contains(e.point) == true -> onPlayPause?.invoke()
                    nextHit?.contains(e.point) == true -> onNext?.invoke()
                    seekHit?.let { r ->
                        if (r.contains(e.point)) {
                            onSeek?.invoke(((e.x - r.x).toFloat() / r.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                            true
                        } else false
                    } == true -> Unit
                }
            }
        })
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val overExit = showExitButton && exitHit?.contains(e.point) == true
                if (overExit != hoverExit) {
                    hoverExit = overExit
                    repaint()
                }
                var overControl = -1
                if (controlsEnabled) {
                    overControl = when {
                        prevHit?.contains(e.point) == true -> 0
                        playHit?.contains(e.point) == true -> 1
                        nextHit?.contains(e.point) == true -> 2
                        seekHit?.contains(e.point) == true -> 3
                        else -> -1
                    }
                }
                if (overControl != hoverControl) {
                    hoverControl = overControl
                    repaint()
                }
                cursor = java.awt.Cursor.getPredefinedCursor(
                    if (overExit || overControl >= 0) java.awt.Cursor.HAND_CURSOR
                    else java.awt.Cursor.DEFAULT_CURSOR
                )
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            paintContent(g2, w, h)
            if (showExitButton) paintExitButton(g2, w, h) else exitHit = null
        } finally {
            g2.dispose()
        }
    }

    private fun paintContent(g2: Graphics2D, w: Int, h: Int) {
        val mediaKey = NowPlayingState.media?.let { "${it.id}|${it.path}" }
        if (mediaKey != lastMediaKey) {
            lastMediaKey = mediaKey
            blurCache.clear()
        }
        paintBackground(g2, w, h)
        if (NowPlayingState.media == null || NowPlayingState.media!!.title.isEmpty()) {
            paintIdle(g2, w, h)
            return
        }
        val compact = w < 460 || h < 200
        val pad = if (compact) 10 else (min(w, h) * 0.055f).roundToInt().coerceIn(10, 22)
        if (portrait) {
            // 竖屏：歌词在上，方形封面容器贴底居中，封面最大占屏幕高度的 1/3。
            val square = min(w - pad * 2, (h / 3f).roundToInt()).coerceAtLeast(40)
            val coverX = (w - square) / 2
            val coverY = h - pad - square
            paintCover(g2, square, coverX, coverY)
            if (controlsEnabled) paintControls(g2, coverX, coverY, square) else {
                prevHit = null
                playHit = null
                nextHit = null
                seekHit = null
            }
            val lyricsBottom = (coverY - pad).coerceAtLeast(pad + 40)
            paintLyrics(g2, pad, w - pad * 2, pad, lyricsBottom, compact || w < 460)
            return
        }
        // 横屏：封面容器宽度约为窗口的 1/3，同时不超出可用高度。
        var square = (w / 3f).roundToInt()
        square = min(square, h - pad * 2)
        square = square.coerceAtLeast(40)
        val coverX = pad
        val coverY = (h - square) / 2
        val lyricLeft = coverX + square + pad
        val lyricW = w - lyricLeft - pad
        if (lyricW < 60) return
        paintCover(g2, square, coverX, coverY)
        if (controlsEnabled) paintControls(g2, coverX, coverY, square) else {
            prevHit = null
            playHit = null
            nextHit = null
            seekHit = null
        }
        paintLyrics(g2, lyricLeft, lyricW, 0, h, compact)
    }

    private fun paintExitButton(g2: Graphics2D, w: Int, h: Int) {
        if (w < 180 || h < 60) {
            exitHit = null
            return
        }
        val text = "退出全屏"
        g2.font = font(Font.PLAIN, 12)
        val fm = g2.fontMetrics
        val padX = 12
        val padY = 7
        val chipW = fm.stringWidth(text) + padX * 2
        val chipH = fm.height + padY * 2
        val x = w - chipW - 14
        val y = 12
        exitHit = java.awt.Rectangle(x, y, chipW, chipH)
        // 鼠标悬停时才显示，避免常驻遮挡画面。
        if (!hoverExit) return
        val shape = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), chipW.toFloat(), chipH.toFloat(), 12f, 12f)
        g2.color = Color(18, 18, 24, 205)
        g2.fill(shape)
        g2.color = Color(255, 255, 255, 55)
        g2.draw(shape)
        g2.color = Color(255, 255, 255, 225)
        g2.drawString(text, x + padX, y + padY + fm.ascent)
    }

    // ---------------------------------------------------------------- 播放控件（封面区域）

    /** 在封面左下区域叠加“上一首 / 播放暂停 / 下一首 + 进度条”，点击事件交给外部回调执行。 */
    private fun paintControls(g2: Graphics2D, coverX: Int, coverY: Int, square: Int) {
        val radius = (square * 0.08f).roundToInt().coerceAtLeast(8)
        val barH = (square * 0.26f).roundToInt().coerceIn(28, square / 2)
        val barY = coverY + square - barH
        val clip = RoundRectangle2D.Float(
            coverX.toFloat(), coverY.toFloat(), square.toFloat(), square.toFloat(),
            radius.toFloat(), radius.toFloat(),
        )
        val oldClip = g2.clip
        g2.clip = clip
        g2.paint = GradientPaint(
            0f, barY.toFloat(), Color(0, 0, 0, 0),
            0f, (barY + barH).toFloat(), Color(0, 0, 0, 190),
        )
        g2.fillRect(coverX, barY, square, barH)
        g2.clip = oldClip

        val btn = (square * 0.16f).roundToInt().coerceIn(16, 60)
        val gap = (square * 0.05f).roundToInt().coerceIn(4, 22)
        val totalW = btn * 3 + gap * 2
        val startX = coverX + (square - totalW) / 2
        val rowCenterY = barY + (barH * 0.38f).roundToInt()
        val icon = btn * 0.5f * 0.72f

        val prevCx = startX + btn / 2
        val playCx = startX + btn + gap + btn / 2
        val nextCx = startX + btn * 2 + gap * 2 + btn / 2
        prevHit = controlHit(prevCx, rowCenterY, btn)
        playHit = controlHit(playCx, rowCenterY, btn)
        nextHit = controlHit(nextCx, rowCenterY, btn)

        drawControlCircle(g2, prevCx, rowCenterY, btn, hoverControl == 0)
        drawControlCircle(g2, playCx, rowCenterY, btn, hoverControl == 1)
        drawControlCircle(g2, nextCx, rowCenterY, btn, hoverControl == 2)
        drawSkipIcon(g2, prevCx.toFloat(), rowCenterY.toFloat(), icon, previous = true)
        if (NowPlayingState.playing) drawPauseIcon(g2, playCx.toFloat(), rowCenterY.toFloat(), icon)
        else drawPlayIcon(g2, playCx.toFloat(), rowCenterY.toFloat(), icon)
        drawSkipIcon(g2, nextCx.toFloat(), rowCenterY.toFloat(), icon, previous = false)

        val duration = NowPlayingState.media?.duration ?: 0L
        val position = NowPlayingState.currentPositionMs()
        val fraction = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val barLeft = coverX + (square * 0.10f).roundToInt()
        val barWidth = square - (square * 0.20f).roundToInt()
        val thickness = (square * 0.016f).roundToInt().coerceIn(2, 5)
        val barTop = coverY + square - (square * 0.07f).roundToInt()
        g2.color = Color(255, 255, 255, 72)
        g2.fillRoundRect(barLeft, barTop, barWidth, thickness, thickness, thickness)
        val fillWidth = (barWidth * fraction).roundToInt()
        if (fillWidth > 0) {
            g2.color = progressColor()
            g2.fillRoundRect(barLeft, barTop, fillWidth, thickness, thickness, thickness)
        }
        seekHit = java.awt.Rectangle(barLeft - 8, barTop - 10, barWidth + 16, thickness + 20)
    }

    private fun controlHit(cx: Int, cy: Int, btn: Int): java.awt.Rectangle {
        val pad = 4
        return java.awt.Rectangle(cx - btn / 2 - pad, cy - btn / 2 - pad, btn + pad * 2, btn + pad * 2)
    }

    private fun drawControlCircle(g2: Graphics2D, cx: Int, cy: Int, btn: Int, hover: Boolean) {
        val radius = btn / 2
        g2.color = Color(255, 255, 255, if (hover) 54 else 28)
        g2.fillOval(cx - radius, cy - radius, btn, btn)
    }

    private fun drawPlayIcon(g2: Graphics2D, cx: Float, cy: Float, s: Float) {
        val path = java.awt.geom.Path2D.Float()
        path.moveTo(cx - s * 0.34f, cy - s * 0.72f)
        path.lineTo(cx - s * 0.34f, cy + s * 0.72f)
        path.lineTo(cx + s * 0.62f, cy)
        path.closePath()
        g2.color = Color(255, 255, 255, 238)
        g2.fill(path)
    }

    private fun drawPauseIcon(g2: Graphics2D, cx: Float, cy: Float, s: Float) {
        val barW = (s * 0.30f).toInt().coerceAtLeast(2)
        val barH = (s * 1.46f).toInt().coerceAtLeast(2)
        val left = (cx - s * 0.46f).toInt()
        val right = (cx + s * 0.16f).toInt()
        g2.color = Color(255, 255, 255, 238)
        g2.fillRoundRect(left, (cy - barH / 2f).toInt(), barW, barH, 2, 2)
        g2.fillRoundRect(right, (cy - barH / 2f).toInt(), barW, barH, 2, 2)
    }

    private fun drawSkipIcon(g2: Graphics2D, cx: Float, cy: Float, s: Float, previous: Boolean) {
        val dir = if (previous) -1f else 1f
        val h = s * 0.70f
        val baseX = cx + dir * 0.16f * s
        val apexX = cx - dir * 0.38f * s
        val barLeft = if (previous) cx - s * 0.62f else cx + s * 0.36f
        val barW = s * 0.26f
        g2.color = Color(255, 255, 255, 238)
        g2.fillRoundRect(barLeft.toInt(), (cy - h).toInt(), barW.toInt().coerceAtLeast(2), (h * 2f).toInt(), 2, 2)
        val path = java.awt.geom.Path2D.Float()
        path.moveTo(baseX, cy - h)
        path.lineTo(baseX, cy + h)
        path.lineTo(apexX, cy)
        path.closePath()
        g2.fill(path)
    }

    // ---------------------------------------------------------------- 背景

    private fun paintBackground(g2: Graphics2D, w: Int, h: Int) {
        val colors = NowPlayingState.colors
        g2.paint = GradientPaint(0f, 0f, Color(10, 11, 15), w.toFloat(), h * 1.35f, Color(5, 5, 8))
        g2.fillRect(0, 0, w, h)

        // AMLL 风格：从封面取出的 4 个暗色做低透明度斜向渐变。
        val palette = colors.palette
        if (palette.size >= 4) {
            g2.paint = LinearGradientPaint(
                0f, 0f, w.toFloat(), h.toFloat(),
                floatArrayOf(0f, 0.35f, 0.70f, 1f),
                Array(4) { i -> withAlpha(palette[i], 44) },
            )
            g2.fillRect(0, 0, w, h)
        }

        val glow = colors.glow
        g2.paint = RadialGradientPaint(
            Point((w * 0.16f).roundToInt(), (h * 0.22f).roundToInt()),
            max(w, h) * 0.85f,
            floatArrayOf(0f, 1f),
            arrayOf(
                Color(glow.red, glow.green, glow.blue, 85),
                Color(glow.red, glow.green, glow.blue, 0)
            )
        )
        g2.fillRect(0, 0, w, h)
    }

    private fun withAlpha(color: Color, alpha: Int): Color =
        Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

    private fun paintIdle(g2: Graphics2D, w: Int, h: Int) {
        val size = (w * 0.045f).roundToInt().coerceIn(14, 26)
        g2.font = font(Font.PLAIN, size)
        g2.color = Color(255, 255, 255, 150)
        val text = "等待播放"
        val tw = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, (w - tw) / 2, (h + g2.fontMetrics.ascent) / 2)
    }

    // ---------------------------------------------------------------- 封面容器

    private fun paintCover(g2: Graphics2D, square: Int, x: Int, y: Int) {
        val radius = (square * 0.08f).roundToInt().coerceAtLeast(8)
        val container = RoundRectangle2D.Float(
            x.toFloat(), y.toFloat(), square.toFloat(), square.toFloat(), radius.toFloat(), radius.toFloat()
        )
        g2.color = Color(255, 255, 255, 14)
        g2.fill(container)
        g2.color = Color(255, 255, 255, 36)
        g2.draw(container)

        val inset = (square * 0.045f).roundToInt().coerceAtLeast(3)
        val size = square - inset * 2
        val cover = RoundRectangle2D.Float(
            (x + inset).toFloat(), (y + inset).toFloat(), size.toFloat(), size.toFloat(),
            (radius * 0.82f), (radius * 0.82f)
        )
        val oldClip = g2.clip
        g2.clip = cover
        val image = NowPlayingState.cover
        if (image != null) {
            drawCenterCrop(g2, image, x + inset, y + inset, size)
        } else {
            g2.color = Color(255, 255, 255, 26)
            g2.fill(cover)
            g2.font = font(Font.BOLD, (size * 0.42f).roundToInt().coerceAtLeast(10))
            g2.color = Color(255, 255, 255, 90)
            val note = "♪"
            val nw = g2.fontMetrics.stringWidth(note)
            g2.drawString(note, x + inset + (size - nw) / 2, y + inset + (size + g2.fontMetrics.ascent) / 2 - 2)
        }
        g2.clip = oldClip
    }

    private fun drawCenterCrop(g2: Graphics2D, image: BufferedImage, x: Int, y: Int, size: Int) {
        val scale = max(size.toFloat() / image.width, size.toFloat() / image.height)
        val dw = (image.width * scale).roundToInt()
        val dh = (image.height * scale).roundToInt()
        g2.drawImage(image, x + (size - dw) / 2, y + (size - dh) / 2, dw, dh, null)
    }

    // ---------------------------------------------------------------- 歌词

    private var lastActiveIdx = -1
    private var animOffset = 0f
    private var animOffsetVel = 0f
    private var animPulse = 0f
    private var animPulseVel = 0f
    private var lastFrameNanos = 0L

    /** 弹簧动画：用于歌词区块的滚动过渡和当前行的弹入。 */
    private fun updateLyricAnimation(idx: Int, step: Int, translationHeight: Int, nowNanos: Long) {
        val dt = if (lastFrameNanos == 0L) 0.016f
        else ((nowNanos - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastFrameNanos = nowNanos

        if (idx != lastActiveIdx && lastActiveIdx >= 0) {
            val jump = idx - lastActiveIdx
            val shift = jump * (step + translationHeight)
            if (abs(jump) <= 2) {
                // 让新歌词从它“原本所在的位置”起步，再弹回当前行位置；跨度太大（如跳转）直接归位。
                animOffset += shift
                animOffsetVel = 0f
            } else {
                animOffset = 0f
                animOffsetVel = 0f
            }
        }
        if (idx != lastActiveIdx) {
            animPulse = 1f
            animPulseVel = 0f
            lastActiveIdx = idx
        }

        var v = animOffsetVel - (220f * animOffset + 26f * animOffsetVel) * dt
        animOffset += v * dt
        animOffsetVel = v

        var pv = animPulseVel - (160f * animPulse + 16f * animPulseVel) * dt
        animPulse += pv * dt
        animPulseVel = pv
    }

    private fun paintLyrics(
        g2: Graphics2D,
        left: Int,
        width: Int,
        top: Int,
        bottom: Int,
        compact: Boolean,
    ) {
        val regionHeight = (bottom - top).coerceAtLeast(40)
        val lines = NowPlayingState.lines
        val pos = NowPlayingState.currentPositionMs()
        if (lines.isEmpty()) {
            paintNoLyrics(g2, left, width, top, bottom, compact)
            return
        }
        var idx = activeIndex(lines, pos)
        if (idx < 0) {
            idx = lines.indexOfFirst { it.pureMainText.isNotBlank() }
            if (idx < 0) {
                paintNoLyrics(g2, left, width, top, bottom, compact)
                return
            }
        }
        val current = lines[idx]
        if (current.pureMainText.isBlank()) {
            paintNoLyrics(g2, left, width, top, bottom, compact)
            return
        }

        val activeSize = if (compact) (min(regionHeight, width) * 0.10f).roundToInt().coerceIn(12, 20)
        else (min(regionHeight, width) * 0.108f).roundToInt().coerceIn(16, 36)
        val translation = current.pureSubText?.takeIf { it.isNotBlank() }

        // 先容纳"最多三行"再决定字号：宁可换行，也不要为了塞进一行把字压得过小。
        val minSize = if (compact) 10 else 13
        val maxBlockHeight = (regionHeight * if (compact) 0.46f else 0.40f).roundToInt()
        var size = activeSize
        var wrapped = wrapLyricText(g2, current.pureMainText, width, size)
        var translationSize = translationSizeFor(size, compact)
        var lineHeight = getFontMetrics(font(Font.BOLD, size)).height
        while (size > minSize) {
            val translationHeight =
                if (translation != null) getFontMetrics(font(Font.PLAIN, translationSize)).height else 0
            lineHeight = getFontMetrics(font(Font.BOLD, size)).height
            val blockHeight = (wrapped.size * lineHeight + translationHeight).toFloat()
            if (wrapped.size <= MAX_WRAPPED_LINES && blockHeight <= maxBlockHeight) break
            size--
            wrapped = wrapLyricText(g2, current.pureMainText, width, size)
            translationSize = translationSizeFor(size, compact)
        }
        if (wrapped.isEmpty()) {
            paintNoLyrics(g2, left, width, top, bottom, compact)
            return
        }
        if (wrapped.size > MAX_WRAPPED_LINES) {
            val kept = wrapped.take(MAX_WRAPPED_LINES).toMutableList()
            kept[kept.lastIndex] = truncateChunk(g2, kept.last(), size, width)
            wrapped = kept
        }

        val translationHeight =
            if (translation != null) getFontMetrics(font(Font.PLAIN, translationSize)).height else 0

        val fm = getFontMetrics(font(Font.BOLD, size))
        val lineStep = lineHeight
        // 当前歌词块首行中心固定在歌词区域高度的 1/4（竖屏 1/3）处，换行后继续向下排。
        val anchorFraction = if (portrait) 0.32f else 0.25f
        val blockTop = (top + regionHeight * anchorFraction).roundToInt() - lineStep / 2
        val firstBaseline = blockTop + (lineStep + fm.ascent - fm.descent) / 2
        val blockBottom = blockTop + wrapped.size * lineStep

        val pad = if (compact) 8 else (regionHeight * 0.03f).roundToInt().coerceIn(6, 14)
        val inactiveSize = (size * 0.52f).roundToInt().coerceAtLeast(9)
        val inactiveFm = getFontMetrics(font(Font.PLAIN, inactiveSize))

        updateLyricAnimation(idx, lineStep, translationHeight, System.nanoTime())
        val offset = animOffset.roundToInt()

        // 上一句：空间不够时省略（紧凑布局下当前行已折成多行时也不画），避免压到封面或越界。
        val prev = lines.getOrNull(idx - 1)
        if (prev != null && prev.pureMainText.isNotBlank() && !(compact && wrapped.size >= 2)) {
            val prevBaseline = blockTop - 6 + offset
            if (prevBaseline - (pad + inactiveFm.ascent) >= top.toFloat()) {
                drawBlurredLine(g2, prev.pureMainText, inactiveSize, 120, left, width, prevBaseline, distance = 1)
            }
        }

        // 下一句区域从"当前块 + 翻译"下方开始排，最多三句。
        val nextTop = blockBottom + translationHeight
        val below = (bottom - pad - nextTop).coerceAtLeast(1)
        var nextStep = (inactiveFm.height * 1.55f).roundToInt()
        nextStep = min(nextStep, (below / 3.0f).roundToInt().coerceAtLeast(1))
        nextStep = nextStep.coerceAtLeast((inactiveFm.height * 1.05f).roundToInt())
        val nextAlphas = intArrayOf(200, 150, 105)
        for (k in 1..3) {
            val next = lines.getOrNull(idx + k) ?: break
            if (next.pureMainText.isBlank()) continue
            val baseline = nextTop + nextStep * k + offset
            if (baseline - inactiveFm.ascent > bottom - pad) break
            drawBlurredLine(g2, next.pureMainText, inactiveSize, nextAlphas[k - 1], left, width, baseline, distance = k)
        }

        if (translation != null) {
            val translationBaseline = blockBottom + translationSize + 2 + offset
            drawTranslation(g2, translation, translationSize, translationBaseline, left, width)
        }

        // 当前歌词块整体做 AMLL 式弹入缩放。
        val scale = 1f + 0.045f * animPulse
        val centerX = left + width / 2.0
        val centerY = (blockTop + blockBottom) / 2.0
        val oldTransform = g2.transform
        if (scale != 1f) {
            g2.translate(centerX, centerY)
            g2.scale(scale.toDouble(), scale.toDouble())
            g2.translate(-centerX, -centerY)
        }
        g2.font = font(Font.BOLD, size)
        for (i in wrapped.indices) {
            val chunk = wrapped[i]
            val baseline = firstBaseline + i * lineStep + offset
            val textW = fm.stringWidth(chunk.text)
            val x = left + (width - textW) / 2f
            g2.color = Color(255, 255, 255, 235)
            g2.drawString(chunk.text, x, baseline.toFloat())

            val progress = lineProgressForRange(current, pos, chunk.start, chunk.end)
            if (progress > 0f) {
                val clipW = (textW * progress).roundToInt().coerceAtLeast(1)
                val oldClip = g2.clip
                g2.clip = java.awt.Rectangle(x.toInt(), baseline - fm.ascent - 6, clipW, fm.height + 12)
                val fill = progressColor()
                g2.color = fill
                g2.drawString(chunk.text, x, baseline.toFloat())
                // 已唱部分柔光描边，参考 AMLL 高亮发光。
                runCatching {
                    val glyphs = g2.font.createGlyphVector(g2.fontRenderContext, chunk.text)
                    val outline = glyphs.getOutline(x, baseline.toFloat())
                    g2.stroke = java.awt.BasicStroke(
                        3f,
                        java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND,
                    )
                    g2.color = Color(fill.red, fill.green, fill.blue, 70)
                    g2.draw(outline)
                }
                g2.clip = oldClip
            }
        }
        g2.transform = oldTransform
    }

    /** 当前行的翻译，显示在当前行正下方。 */
    private fun drawTranslation(
        g2: Graphics2D,
        text: String,
        size: Int,
        baseline: Int,
        left: Int,
        regionWidth: Int,
    ) {
        val translationFont = font(Font.PLAIN, size)
        g2.font = translationFont
        val fitted = fitTextWithFont(g2, text, translationFont, regionWidth)
        val fm = g2.fontMetrics
        val tw = fm.stringWidth(fitted)
        g2.color = withAlpha(NowPlayingState.colors.lyric, 150)
        g2.drawString(fitted, left + (regionWidth - tw) / 2, baseline)
    }

    private fun paintNoLyrics(
        g2: Graphics2D,
        left: Int,
        width: Int,
        top: Int,
        bottom: Int,
        compact: Boolean,
    ) {
        g2.font = font(Font.PLAIN, if (compact) 11 else 15)
        g2.color = Color(255, 255, 255, 110)
        val text = "暂无歌词"
        val tw = g2.fontMetrics.stringWidth(text)
        val centerY = (top + (bottom - top) / 2.0).roundToInt()
        g2.drawString(text, left + (width - tw) / 2, centerY + g2.fontMetrics.ascent / 2)
    }

    /** 非当前行：先渲染成图片再做高斯模糊，带缓存避免逐帧重复计算；模糊强度对齐 AMLL。 */
    /** 当前 Graphics2D 对应的设备像素比例（HiDPI 下为 1.25/1.5/2 等）。 */
    private fun deviceScale(g2: Graphics2D): Float {
        val fromTransform = g2.transform.scaleX.toFloat()
        val fromConfig = runCatching { graphicsConfiguration.defaultTransform.scaleX.toFloat() }.getOrDefault(1f)
        return max(fromTransform, fromConfig).coerceIn(1f, 4f)
    }

    private fun drawBlurredLine(
        g2: Graphics2D,
        text: String,
        size: Int,
        alpha: Int,
        left: Int,
        regionWidth: Int,
        baseline: Int,
        distance: Int,
    ) {
        // AMLL 的 sigma 是按它的大字号（约 42px）标定的；我们的非当前行字号更小，
        // 若直接套用会显得过糊，因此按字号比例收敛，保持"轻微虚化但可辨识"。
        val sigma = amllBlurSigma(distance) * (size / 42f).coerceIn(0.45f, 1f)
        val scale = deviceScale(g2)
        val image = blurredLineImage(
            g2, text, size, alpha, sigma, scale, regionWidth, NowPlayingState.colors.lyric,
        ) ?: return
        val fm = getFontMetrics(font(Font.PLAIN, size))
        // AMLL 非活动行统一缩到 0.97，靠模糊和透明度体现纵深。
        val shrink = 0.97f
        val dw = (image.width / scale * shrink).roundToInt()
        val dh = (image.height / scale * shrink).roundToInt()
        val drawX = left + ((regionWidth - dw) / 2f).roundToInt()
        val kernelPx = Math.ceil((sigma * scale * 2.2f).toDouble()).toInt().coerceAtLeast(1)
        val padLogical = (kernelPx + 2) / scale
        val drawY = (baseline - (padLogical + fm.ascent) * shrink).roundToInt()
        g2.drawImage(image, drawX, drawY, dw, dh, null)
    }

    /** AMLL 的歌词模糊公式：半径 = 1.1d + 0.2d²（d 为与当前行的距离），上限 12px。 */
    private fun amllBlurSigma(distance: Int): Float {
        val depth = distance.coerceIn(0, 6).toFloat()
        return (depth * 1.1f + depth * depth * 0.2f).coerceAtMost(12f)
    }

    private fun blurredLineImage(
        g2: Graphics2D,
        text: String,
        size: Int,
        alpha: Int,
        sigma: Float,
        deviceScale: Float,
        maxWidth: Int,
        color: Color,
    ): BufferedImage? {
        val sizePx = (size * deviceScale).roundToInt().coerceAtLeast(1)
        val sigmaPx = sigma * deviceScale
        val kernelPx = Math.ceil((sigmaPx * 2.2f).toDouble()).toInt().coerceAtLeast(1)
        val padPx = kernelPx + 2
        val maxWidthPx = (maxWidth * deviceScale).roundToInt().coerceAtLeast(1)
        val key = "$sizePx|$alpha|${(sigmaPx * 4f).roundToInt()}|$kernelPx|$maxWidthPx|${color.rgb}|$text"
        blurCache[key]?.let { return it }
        if (blurCache.size > 48) blurCache.clear()

        val textFont = font(Font.PLAIN, sizePx)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val pg = probe.createGraphics()
        val fitted: String
        val fm: FontMetrics
        try {
            pg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            pg.font = textFont
            fitted = fitTextInPixels(pg, text, maxWidthPx)
            fm = pg.fontMetrics
        } finally {
            pg.dispose()
        }
        val width = (fm.stringWidth(fitted) + padPx * 2).coerceAtLeast(1)
        val height = (fm.height + padPx * 2).coerceAtLeast(1)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val ig = image.createGraphics()
        try {
            ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            ig.font = textFont
            ig.color = Color(color.red, color.green, color.blue, alpha)
            ig.drawString(fitted, padPx, padPx + fm.ascent)
        } finally {
            ig.dispose()
        }
        val result = if (sigmaPx > 0.2f) gaussianBlur(image, sigmaPx, kernelPx) else image
        blurCache[key] = result
        return result
    }

    /** 设备像素坐标下的省略号截断（用于设备分辨率渲染的模糊图）。 */
    private fun fitTextInPixels(g: Graphics2D, text: String, maxWidthPx: Int): String {
        if (text.isEmpty()) return text
        val fm = g.fontMetrics
        if (fm.stringWidth(text) <= maxWidthPx) return text
        var end = text.length
        while (end > 0 && fm.stringWidth(text.substring(0, end) + "…") > maxWidthPx) end--
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }

    private fun gaussianBlur(source: BufferedImage, sigma: Float, radius: Int): BufferedImage {
        val size = radius * 2 + 1
        val kernel = FloatArray(size * size)
        var sum = 0f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x - radius).toFloat()
                val dy = (y - radius).toFloat()
                val value = exp(-(dx * dx + dy * dy) / (2f * sigma * sigma))
                kernel[y * size + x] = value
                sum += value
            }
        }
        if (sum <= 0f) return source
        for (i in kernel.indices) kernel[i] /= sum
        val op = ConvolveOp(Kernel(size, size, kernel), ConvolveOp.EDGE_ZERO_FILL, null)
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        return op.filter(source, target)
    }

    /** 已唱进度填充色：直接用 AMLL 式取色得到的 glow（亮度 0.76、彩度更高）。 */
    private fun progressColor(): Color = NowPlayingState.colors.glow

    // ---------------------------------------------------------------- 歌词换行

    private data class WrappedChunk(val text: String, val start: Int, val end: Int)

    private fun translationSizeFor(currentSize: Int, compact: Boolean): Int =
        (currentSize * if (compact) 0.62f else 0.58f).roundToInt()
            .coerceAtLeast(if (compact) 8 else 10)

    /** 按宽度把当前歌词折成最多三行；返回每行在原文中的字符区间，便于逐字进度映射。 */
    private fun wrapLyricText(g2: Graphics2D, text: String, maxWidth: Int, size: Int): List<WrappedChunk> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val base = text.indexOf(trimmed).coerceAtLeast(0)
        val textFont = font(Font.BOLD, size)
        g2.font = textFont
        val fm = g2.fontMetrics

        val tokens = lyricTokens(trimmed)
        if (tokens.isEmpty()) return emptyList()
        val chunks = ArrayList<WrappedChunk>()
        var lineStart = tokens.first().first
        var lineEnd = tokens.first().last + 1

        fun flush() {
            var end = lineEnd
            while (end > lineStart && trimmed[end - 1].isWhitespace()) end--
            if (end > lineStart) {
                chunks += WrappedChunk(trimmed.substring(lineStart, end), base + lineStart, base + end)
            }
        }

        for (t in 1 until tokens.size) {
            val tok = tokens[t]
            if (fm.stringWidth(trimmed.substring(lineStart, tok.last + 1).trimEnd()) <= maxWidth) {
                lineEnd = tok.last + 1
                continue
            }
            val tokenText = trimmed.substring(tok.first, tok.last + 1)
            if (tokenText.isNotEmpty() && isClosingPunctuation(tokenText[0])) {
                // 行末标点跟随上一行，避免出现行首标点。
                lineEnd = tok.last + 1
                continue
            }
            flush()
            var nextStart = tok.first
            while (nextStart <= tok.last && trimmed[nextStart].isWhitespace()) nextStart++
            lineStart = nextStart
            lineEnd = tok.last + 1
        }
        flush()
        return chunks
    }

    private fun truncateChunk(g2: Graphics2D, chunk: WrappedChunk, size: Int, maxWidth: Int): WrappedChunk {
        val textFont = font(Font.BOLD, size)
        g2.font = textFont
        val fm = g2.fontMetrics
        if (fm.stringWidth(chunk.text) <= maxWidth) return chunk
        var end = chunk.text.length
        while (end > 0 && fm.stringWidth(chunk.text.substring(0, end) + "…") > maxWidth) end--
        val text = if (end <= 0) "…" else chunk.text.substring(0, end) + "…"
        return WrappedChunk(text, chunk.start, chunk.start + end)
    }

    private fun lyricTokens(text: String): List<IntRange> {
        val tokens = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val cc = Character.charCount(cp)
            when {
                Character.isWhitespace(cp) -> {
                    var j = i
                    while (j < text.length) {
                        val c = text.codePointAt(j)
                        if (!Character.isWhitespace(c)) break
                        j += Character.charCount(c)
                    }
                    tokens += i until j
                    i = j
                }
                isCjkCodePoint(cp) -> {
                    tokens += i until i + cc
                    i += cc
                }
                isWordCodePoint(cp) -> {
                    var j = i
                    while (j < text.length) {
                        val c = text.codePointAt(j)
                        if (!isWordCodePoint(c)) break
                        j += Character.charCount(c)
                    }
                    tokens += i until j
                    i = j
                }
                else -> {
                    tokens += i until i + cc
                    i += cc
                }
            }
        }
        return tokens
    }

    private fun isCjkCodePoint(cp: Int): Boolean =
        Character.isIdeographic(cp) || cp in 0x3000..0x9FFF || cp in 0xAC00..0xD7AF ||
            cp in 0xF900..0xFAFF || cp in 0x20000..0x2FA1F

    private fun isWordCodePoint(cp: Int): Boolean =
        !isCjkCodePoint(cp) && (Character.isLetterOrDigit(cp) || cp == '\''.code || cp == 0x2019 || cp == '-'.code)

    private fun isClosingPunctuation(ch: Char): Boolean =
        ch in "，。！？；：、,.!?;:)]}）】》」』”’…—~·"

    /** 单行已唱进度：按字符区间把逐字 cells 的权重切开，换行后每行都能独立渲染进度。 */
    private fun lineProgressForRange(
        line: PlaybackExtensionPoint.LyricsLine,
        pos: Long,
        start: Int,
        end: Int,
    ): Float {
        if (pos <= line.startTime) return 0f
        if (pos >= line.endTime) return 1f
        val cells = line.lyricsCells
        if (cells.isNotEmpty()) {
            val joined = buildString { cells.forEach { append(it.text) } }
            if (joined == line.pureMainText) {
                var offset = 0
                var total = 0f
                var acc = 0f
                for (cell in cells) {
                    val cellStart = offset
                    val cellEnd = offset + cell.text.length
                    offset = cellEnd
                    val from = max(cellStart, start)
                    val to = min(cellEnd, end)
                    if (to <= from) continue
                    val weight = (to - from).toFloat()
                    total += weight
                    if (pos >= cell.endTime) {
                        acc += weight
                    } else if (pos > cell.startTime) {
                        val span = (cell.endTime - cell.startTime).coerceAtLeast(1L).toFloat()
                        acc += weight * ((pos - cell.startTime).toFloat() / span).coerceIn(0f, 1f)
                    }
                }
                if (total > 0f) return (acc / total).coerceIn(0f, 1f)
            }
        }
        return lineProgress(line, pos)
    }

    private fun fitTextWithFont(g2: Graphics2D, text: String, textFont: Font, maxWidth: Int): String {
        if (text.isEmpty()) return text
        val fm = getFontMetrics(textFont)
        if (fm.stringWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && fm.stringWidth(text.substring(0, end) + "…") > maxWidth) end--
        return if (end <= 0) "…" else text.substring(0, end) + "…"
    }

    // ---------------------------------------------------------------- 工具

    private fun activeIndex(lines: List<PlaybackExtensionPoint.LyricsLine>, pos: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].startTime <= pos) idx = i else break
        }
        return idx
    }

    private fun lineProgress(line: PlaybackExtensionPoint.LyricsLine, pos: Long): Float {
        if (pos <= line.startTime) return 0f
        if (pos >= line.endTime) return 1f
        val cells = line.lyricsCells
        if (cells.isEmpty()) {
            val span = (line.endTime - line.startTime).coerceAtLeast(1L)
            return ((pos - line.startTime).toFloat() / span).coerceIn(0f, 1f)
        }
        var total = 0f
        var acc = 0f
        for (cell in cells) {
            val weight = cell.text.length.coerceAtLeast(1).toFloat()
            total += weight
            if (pos >= cell.endTime) {
                acc += weight
            } else if (pos > cell.startTime) {
                val span = (cell.endTime - cell.startTime).coerceAtLeast(1L)
                acc += weight * ((pos - cell.startTime).toFloat() / span).coerceIn(0f, 1f)
            }
        }
        return if (total <= 0f) 0f else (acc / total).coerceIn(0f, 1f)
    }

    private fun font(style: Int, size: Int): Font = Font("Microsoft YaHei UI", style, size.coerceAtLeast(8))
}
