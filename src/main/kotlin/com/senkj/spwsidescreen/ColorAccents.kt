package com.senkj.spwsidescreen

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 封面取色，对齐 AMLL-into-SPW 的思路：Oklab 感知空间 + 色相族投票 + 中性回退。
 *
 * 产出三组颜色：
 *  - palette：背景用的 4 个暗色（固定采样点压暗），做低透明度流体渐变；
 *  - lyric：歌词文字色（提亮、降饱和，保证在深色背景上可读）；
 *  - glow：高亮/进度填充色（更饱和，用于已唱部分与光晕）。
 *
 * 参考实现：GaBoron/AMLL-into-SPW 的 cover/CoverLyricColor.kt、cover/OklabColor.kt、
 * host/CoverPaletteReader.kt。
 */
object ColorAccents {

    data class Colors(val palette: List<Color>, val lyric: Color, val glow: Color)

    private val FALLBACK_PALETTE = listOf(
        Color(0x233149), Color(0x494060), Color(0x513B47), Color(0x344A50),
    )
    private val BRAND = Color(0x7C6CF0)

    /** 视为"彩色"的 Oklab chroma 阈值。 */
    private const val CHROMA_FLOOR = 0.035f

    /** 低于该 chroma 视为近中性封面，退纯白歌词 + 品牌色高亮。 */
    private const val NEUTRAL_FLOOR = 0.025f

    fun fallback(): Colors = Colors(FALLBACK_PALETTE, Color.WHITE, brandGlow())

    fun extract(image: BufferedImage): Colors {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0) return fallback()
        val palette = fixedPalette(image)
        val dominant = dominantColor(image)
            ?: return Colors(palette, Color.WHITE, brandGlow())
        if (dominant.chroma < NEUTRAL_FLOOR) {
            return Colors(palette, Color.WHITE, brandGlow())
        }
        val lyric = dominant.toRgb(0.88f, min(dominant.chroma, 0.12f))
        val glow = dominant.toRgb(0.76f, (dominant.chroma * 1.15f).coerceIn(0.08f, 0.24f))
        return Colors(palette, lyric, glow)
    }

    private fun brandGlow(): Color = OklabColor.fromRgb(BRAND.rgb).toRgb(0.76f, 0.11f)

    /** 固定 4 个归一化采样点（与 AMLL 相同），压暗后作为背景 palette。 */
    private fun fixedPalette(image: BufferedImage): List<Color> {
        val points = floatArrayOf(
            0.20f, 0.20f,
            0.75f, 0.25f,
            0.25f, 0.75f,
            0.70f, 0.70f,
        )
        val result = ArrayList<Color>(4)
        for (i in 0 until 4) {
            val x = (points[i * 2] * image.width).toInt().coerceIn(0, image.width - 1)
            val y = (points[i * 2 + 1] * image.height).toInt().coerceIn(0, image.height - 1)
            val rgb = image.getRGB(x, y)
            val alpha = (rgb ushr 24) and 0xFF
            result += if (alpha < 128) FALLBACK_PALETTE[i] else darken(rgb)
        }
        return result
    }

    private fun darken(rgb: Int): Color {
        val r = ((rgb shr 16) and 0xFF) * 0.5 + 12
        val g = ((rgb shr 8) and 0xFF) * 0.5 + 12
        val b = (rgb and 0xFF) * 0.5 + 12
        return Color(
            r.roundToInt().coerceIn(0, 255),
            g.roundToInt().coerceIn(0, 255),
            b.roundToInt().coerceIn(0, 255),
        )
    }

    /**
     * 约 96x96 步进扫描封面，把彩色像素按 24 个色相族投票（chroma 越大权重越高），
     * 取票数最高的族在 Oklab 空间做加权平均；没有彩色像素时用中性像素兜底。
     */
    private fun dominantColor(image: BufferedImage): OklabColor? {
        val w = image.width
        val h = image.height
        val step = ((max(w, h) + 95) / 96).coerceAtLeast(1)

        val hueWeight = FloatArray(24)
        val hueL = FloatArray(24)
        val hueA = FloatArray(24)
        val hueB = FloatArray(24)
        var neutralWeight = 0f
        var neutralL = 0f
        var neutralA = 0f
        var neutralB = 0f

        var x = 0
        while (x < w) {
            var y = 0
            while (y < h) {
                val rgb = image.getRGB(x, y)
                val alpha = (rgb ushr 24) and 0xFF
                if (alpha >= 128) {
                    val oklab = OklabColor.fromRgb(rgb)
                    if (oklab.lightness in 0.12f..0.95f) {
                        val chroma = oklab.chroma
                        if (chroma >= CHROMA_FLOOR) {
                            val hue = (Math.toDegrees(atan2(oklab.b, oklab.a).toDouble()) + 360.0) % 360.0
                            val bin = (hue / 15.0).toInt().coerceIn(0, 23)
                            val weight = 0.5f + min(chroma, 0.25f) * 2f
                            hueWeight[bin] += weight
                            hueL[bin] += oklab.lightness * weight
                            hueA[bin] += oklab.a * weight
                            hueB[bin] += oklab.b * weight
                        } else {
                            neutralWeight += 1f
                            neutralL += oklab.lightness
                            neutralA += oklab.a
                            neutralB += oklab.b
                        }
                    }
                }
                y += step
            }
            x += step
        }

        var best = -1
        for (i in 0 until 24) {
            if (hueWeight[i] > 0f && (best < 0 || hueWeight[i] > hueWeight[best])) best = i
        }
        if (best >= 0) {
            val wgt = hueWeight[best].coerceAtLeast(1e-6f)
            return OklabColor(hueL[best] / wgt, hueA[best] / wgt, hueB[best] / wgt)
        }
        if (neutralWeight > 0f) {
            return OklabColor(
                neutralL / neutralWeight,
                neutralA / neutralWeight,
                neutralB / neutralWeight,
            )
        }
        return null
    }
}

/** Oklab 色彩空间的最小实现（sRGB <-> Oklab，可按指定亮度/彩度回写）。 */
internal data class OklabColor(val lightness: Float, val a: Float, val b: Float) {

    val chroma: Float
        get() = kotlin.math.sqrt(a * a + b * b)

    /**
     * 保留色相，按指定亮度与彩度回写 sRGB。
     * 近中性色（chroma 约等于 0）回写为灰阶。
     */
    fun toRgb(targetLightness: Float = lightness, targetChroma: Float = chroma): Color {
        val hue = atan2(b, a)
        val a2 = cos(hue) * targetChroma
        val b2 = sin(hue) * targetChroma

        val lPrime = targetLightness + 0.3963377774f * a2 + 0.2158037573f * b2
        val mPrime = targetLightness - 0.1055613458f * a2 - 0.0638541728f * b2
        val sPrime = targetLightness - 0.0894841775f * a2 - 1.2914855480f * b2

        val l3 = lPrime * lPrime * lPrime
        val m3 = mPrime * mPrime * mPrime
        val s3 = sPrime * sPrime * sPrime

        val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        val b3 = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3

        return Color(linearToSrgb8(r), linearToSrgb8(g), linearToSrgb8(b3))
    }

    companion object {
        fun fromRgb(rgb: Int): OklabColor {
            val r = srgbToLinear(((rgb shr 16) and 0xFF) / 255f)
            val g = srgbToLinear(((rgb shr 8) and 0xFF) / 255f)
            val b = srgbToLinear((rgb and 0xFF) / 255f)

            val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
            val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
            val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

            val lPrime = cbrt(l)
            val mPrime = cbrt(m)
            val sPrime = cbrt(s)

            return OklabColor(
                lightness = 0.2104542553f * lPrime + 0.7936177850f * mPrime - 0.0040720468f * sPrime,
                a = 1.9779984951f * lPrime - 2.4285922050f * mPrime + 0.4505937099f * sPrime,
                b = 0.0259040371f * lPrime + 0.7827717662f * mPrime - 0.8086757660f * sPrime,
            )
        }

        private fun srgbToLinear(value: Float): Float =
            if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

        private fun linearToSrgb8(value: Float): Int {
            val clamped = value.coerceIn(0f, 1f)
            val srgb = if (clamped <= 0.0031308f) clamped * 12.92f
            else 1.055f * clamped.pow(1f / 2.4f) - 0.055f
            return (srgb * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
    }
}
