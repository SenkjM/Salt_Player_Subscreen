package com.senkj.spwsidescreen

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import java.awt.Color
import java.awt.Font
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

    private var exitHit: java.awt.Rectangle? = null
    private var hoverExit = false

    private val blurCache = HashMap<String, BufferedImage>()
    private var lastMediaKey: String? = null

    init {
        isOpaque = true
        background = Color(0x07070A)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (showExitButton && exitHit?.contains(e.point) == true) onExitClick?.invoke()
            }
        })
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                if (!showExitButton) return
                val over = exitHit?.contains(e.point) == true
                if (over != hoverExit) {
                    hoverExit = over
                    repaint()
                }
                cursor = java.awt.Cursor.getPredefinedCursor(
                    if (over) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.DEFAULT_CURSOR
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
        val pad = if (compact) 10 else (h * 0.055f).roundToInt().coerceIn(10, 22)
        // 封面容器宽度约为窗口的 1/3，同时不超出可用高度。
        var square = (w / 3f).roundToInt()
        square = min(square, h - pad * 2)
        square = square.coerceAtLeast(40)
        val coverX = pad
        val coverY = (h - square) / 2
        val lyricLeft = coverX + square + pad
        val lyricW = w - lyricLeft - pad
        if (lyricW < 60) return
        paintCover(g2, square, coverX, coverY)
        paintLyrics(g2, lyricLeft, lyricW, h, compact)
    }

    private fun paintExitButton(g2: Graphics2D, w: Int, h: Int) {
        if (w < 180 || h < 60) {
            exitHit = null
            return
        }
        val text = "✕ 退出全屏"
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

    private fun paintLyrics(g2: Graphics2D, left: Int, width: Int, h: Int, compact: Boolean) {
        val lines = NowPlayingState.lines
        val pos = NowPlayingState.currentPositionMs()
        if (lines.isEmpty()) {
            paintNoLyrics(g2, left, width, h, compact)
            return
        }
        var idx = activeIndex(lines, pos)
        if (idx < 0) {
            idx = lines.indexOfFirst { it.pureMainText.isNotBlank() }
            if (idx < 0) {
                paintNoLyrics(g2, left, width, h, compact)
                return
            }
        }
        val current = lines[idx]
        if (current.pureMainText.isBlank()) {
            paintNoLyrics(g2, left, width, h, compact)
            return
        }

        val activeSize = if (compact) (h * 0.10f).roundToInt().coerceIn(12, 20)
        else (h * 0.108f).roundToInt().coerceIn(16, 36)
        val translation = current.pureSubText?.takeIf { it.isNotBlank() }

        // 先容纳"最多三行"再决定字号：宁可换行，也不要为了塞进一行把字压得过小。
        val minSize = if (compact) 10 else 13
        val maxBlockHeight = (h * if (compact) 0.46f else 0.40f).roundToInt()
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
            paintNoLyrics(g2, left, width, h, compact)
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
        // 当前歌词块的第一行中心固定在高度 1/4 处，换行后继续向下排。
        val blockTop = (h * 0.25f).roundToInt() - lineStep / 2
        val firstBaseline = blockTop + (lineStep + fm.ascent - fm.descent) / 2
        val blockBottom = blockTop + wrapped.size * lineStep

        val pad = if (compact) 8 else (h * 0.03f).roundToInt().coerceIn(6, 14)
        val inactiveSize = (size * 0.52f).roundToInt().coerceAtLeast(9)
        val inactiveFm = getFontMetrics(font(Font.PLAIN, inactiveSize))

        updateLyricAnimation(idx, lineStep, translationHeight, System.nanoTime())
        val offset = animOffset.roundToInt()

        // 上一句：空间不够时省略（紧凑布局下当前行已折成多行时也不画），避免压到封面或越界。
        val prev = lines.getOrNull(idx - 1)
        if (prev != null && prev.pureMainText.isNotBlank() && !(compact && wrapped.size >= 2)) {
            val prevBaseline = blockTop - 6 + offset
            if (prevBaseline - (pad + inactiveFm.ascent) >= 0f) {
                drawBlurredLine(g2, prev.pureMainText, inactiveSize, 120, left, width, prevBaseline, distance = 1)
            }
        }

        // 下一句区域从"当前块 + 翻译"下方开始排，最多三句。
        val nextTop = blockBottom + translationHeight
        val below = (h - pad - nextTop).coerceAtLeast(1)
        var nextStep = (inactiveFm.height * 1.55f).roundToInt()
        nextStep = min(nextStep, (below / 3.0f).roundToInt().coerceAtLeast(1))
        nextStep = nextStep.coerceAtLeast((inactiveFm.height * 1.05f).roundToInt())
        val nextAlphas = intArrayOf(200, 150, 105)
        for (k in 1..3) {
            val next = lines.getOrNull(idx + k) ?: break
            if (next.pureMainText.isBlank()) continue
            val baseline = nextTop + nextStep * k + offset
            if (baseline - inactiveFm.ascent > h - pad) break
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

    private fun paintNoLyrics(g2: Graphics2D, left: Int, width: Int, h: Int, compact: Boolean) {
        g2.font = font(Font.PLAIN, if (compact) 11 else 15)
        g2.color = Color(255, 255, 255, 110)
        val text = "暂无歌词"
        val tw = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, left + (width - tw) / 2, (h + g2.fontMetrics.ascent) / 2)
    }

    /** 非当前行：先渲染成图片再做高斯模糊，带缓存避免逐帧重复计算；模糊强度对齐 AMLL。 */
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
        val sigma = amllBlurSigma(distance)
        val kernelRadius = Math.ceil(sigma * 2.2).toInt().coerceAtLeast(1)
        val image = blurredLineImage(
            g2, text, size, alpha, sigma, kernelRadius, regionWidth, NowPlayingState.colors.lyric,
        ) ?: return
        val fm = getFontMetrics(font(Font.PLAIN, size))
        // AMLL 非活动行统一缩到 0.97，靠模糊和透明度体现纵深。
        val scale = 0.97f
        val dw = (image.width * scale).roundToInt()
        val dh = (image.height * scale).roundToInt()
        val drawX = left + (regionWidth - dw) / 2
        val pad = kernelRadius + 2
        val drawY = (baseline - (pad + fm.ascent) * scale).roundToInt()
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
        kernelRadius: Int,
        maxWidth: Int,
        color: Color,
    ): BufferedImage? {
        val pad = kernelRadius + 2
        val key = "$size|$alpha|${(sigma * 4f).roundToInt()}|$kernelRadius|$maxWidth|${color.rgb}|$text"
        blurCache[key]?.let { return it }
        if (blurCache.size > 48) blurCache.clear()

        val textFont = font(Font.PLAIN, size)
        val fitted = fitTextWithFont(g2, text, textFont, maxWidth)
        val fm = getFontMetrics(textFont)
        val width = (fm.stringWidth(fitted) + pad * 2).coerceAtLeast(1)
        val height = (fm.height + pad * 2).coerceAtLeast(1)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val ig = image.createGraphics()
        try {
            ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            ig.font = textFont
            ig.color = Color(color.red, color.green, color.blue, alpha)
            ig.drawString(fitted, pad, pad + fm.ascent)
        } finally {
            ig.dispose()
        }
        val result = if (sigma > 0.2f) gaussianBlur(image, sigma, kernelRadius) else image
        blurCache[key] = result
        return result
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
