package com.hayoonjae.asciicam

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 어두운 쪽 → 밝은 쪽 순서로 나열한 글자 집합. */
enum class Ramp(val label: String, val chars: CharArray) {
    CLASSIC("클래식", " .:-=+*#%@".toCharArray()),
    BLOCK("블록", " ░▒▓█".toCharArray()),
    DENSE("촘촘", " .'`^\",:;Il!i~+_-?][}{1)(|tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@".toCharArray()),
    DIGIT("숫자", " .:-017".toCharArray()),
    DOT("점", " ·•●".toCharArray()),
}

/** 글자 색을 어떻게 정할지. */
enum class ColorMode(val label: String) {
    CAMERA("카메라 색"),
    GRADIENT("그라디언트"),
    MONO("단색"),
}

/** 밝고 채도 높은 두 색 그라디언트 프리셋. */
enum class GradientPreset(val label: String, val start: Int, val end: Int) {
    SUNSET("선셋", 0xFFFF6B6B.toInt(), 0xFFFFD93D.toInt()),
    AURORA("오로라", 0xFF00F5A0.toInt(), 0xFF00D9F5.toInt()),
    NEON("네온", 0xFFF72585.toInt(), 0xFF4CC9F0.toInt()),
    LIME("라임", 0xFFA8FF78.toInt(), 0xFF3BE8B0.toInt()),
    CANDY("캔디", 0xFFC471F5.toInt(), 0xFFFA71CD.toInt()),
}

/** 한 프레임의 ASCII 격자. [colors] 는 [ColorMode.CAMERA] 일 때만 채워진다. */
class AsciiFrame(
    val cols: Int,
    val rows: Int,
    val chars: CharArray,
    val colors: IntArray?,
) {
    /** 클립보드/파일용 텍스트. */
    fun toText(): String {
        val sb = StringBuilder((cols + 1) * rows)
        for (r in 0 until rows) {
            sb.append(chars, r * cols, cols)
            sb.append('\n')
        }
        return sb.toString()
    }
}

/** 변환에 영향을 주는 사용자 설정. */
data class Settings(
    val ramp: Ramp = Ramp.CLASSIC,
    val colorMode: ColorMode = ColorMode.GRADIENT,
    val gradient: GradientPreset = GradientPreset.AURORA,
    /** 가로 글자 수. */
    val cols: Int = 90,
    /** 1.0 이 원본. 낮으면 밋밋하고 높으면 대비가 세진다. */
    val contrast: Float = 1.0f,
    val invert: Boolean = false,
    /** 프레임마다 밝기 범위를 0~255 로 펴 준다. */
    val autoLevels: Boolean = true,
    /** 어두운 배경 대신 밝은 배경에 어두운 글자로. */
    val lightBackground: Boolean = false,
    val frontCamera: Boolean = false,
)

/** 출력 격자 크기. 화면을 실제로 재 본 뒤 분석 스레드로 넘겨 준다. */
data class Grid(val cols: Int, val rows: Int, val aspect: Float)

/**
 * YUV_420_888 평면들. ImageProxy 에 의존하지 않아 테스트에서 직접 만들 수 있다.
 * [width]/[height] 는 회전 전 원본 크기다.
 */
class YuvPlanes(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val yPixelStride: Int,
    val u: ByteArray?,
    val v: ByteArray?,
    val uvRowStride: Int,
    val uvPixelStride: Int,
)

object AsciiConverter {

    /** 셀 하나를 가로·세로 이만큼 나눠 평균을 낸다. 화질과 비용의 타협점. */
    private const val SUBSAMPLES = 3

    /** 자동 레벨이 노이즈만 증폭하지 않도록 요구하는 최소 명암 폭. */
    private const val MIN_LEVEL_SPAN = 24

    /** 카메라 색 모드에서 어두운 픽셀 색을 끌어올리는 한계 배율. */
    private const val MAX_COLOR_BOOST = 2.5f

    fun convert(
        planes: YuvPlanes,
        rotationDegrees: Int,
        mirror: Boolean,
        grid: Grid,
        settings: Settings,
    ): AsciiFrame {
        val cols = grid.cols.coerceAtLeast(1)
        val rows = grid.rows.coerceAtLeast(1)
        val rot = ((rotationDegrees % 360) + 360) % 360
        val swapped = rot == 90 || rot == 270
        val rotW = if (swapped) planes.height else planes.width
        val rotH = if (swapped) planes.width else planes.height

        // 회전된 프레임에서 화면 비율에 맞는 가운데 영역만 잘라 쓴다(센터 크롭).
        val srcAspect = rotW.toFloat() / rotH.toFloat()
        val target = if (grid.aspect > 0f) grid.aspect else srcAspect
        var cropW = rotW.toFloat()
        var cropH = rotH.toFloat()
        if (srcAspect > target) cropW = rotH * target else cropH = rotW / target
        val cropX = (rotW - cropW) / 2f
        val cropY = (rotH - cropH) / 2f

        val count = cols * rows
        val lum = IntArray(count)
        val colors = if (settings.colorMode == ColorMode.CAMERA) IntArray(count) else null
        val wantColor = colors != null && planes.u != null && planes.v != null

        for (cy in 0 until rows) {
            for (cx in 0 until cols) {
                // 전면 카메라는 좌우를 뒤집어 거울처럼 보이게 한다.
                val sampleCol = if (mirror) cols - 1 - cx else cx
                var sum = 0
                var n = 0
                for (j in 0 until SUBSAMPLES) {
                    val ry = (cropY + (cy + (j + 0.5f) / SUBSAMPLES) * cropH / rows).toInt()
                    if (ry < 0 || ry >= rotH) continue
                    for (i in 0 until SUBSAMPLES) {
                        val rx = (cropX + (sampleCol + (i + 0.5f) / SUBSAMPLES) * cropW / cols).toInt()
                        if (rx < 0 || rx >= rotW) continue
                        sum += lumaAt(planes, rx, ry, rot)
                        n++
                    }
                }
                val idx = cy * cols + cx
                lum[idx] = if (n > 0) sum / n else 0

                if (wantColor) {
                    val rx = (cropX + (sampleCol + 0.5f) * cropW / cols).toInt().coerceIn(0, rotW - 1)
                    val ry = (cropY + (cy + 0.5f) * cropH / rows).toInt().coerceIn(0, rotH - 1)
                    colors[idx] = colorAt(planes, rx, ry, rot)
                }
            }
        }

        applyLevels(lum, settings)

        val ramp = settings.ramp.chars
        val chars = CharArray(count)
        for (i in 0 until count) {
            val v = lum[i]
            chars[i] = ramp[(v * ramp.size / 256).coerceIn(0, ramp.size - 1)]
        }
        return AsciiFrame(cols, rows, chars, colors)
    }

    /** 자동 레벨 → 대비 → 반전 순으로 밝기 격자를 손본다. 제자리에서 고친다. */
    internal fun applyLevels(lum: IntArray, settings: Settings) {
        if (settings.autoLevels) {
            var lo = 255
            var hi = 0
            for (v in lum) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            val span = hi - lo
            if (span >= MIN_LEVEL_SPAN) {
                for (i in lum.indices) lum[i] = (lum[i] - lo) * 255 / span
            }
        }
        val c = settings.contrast
        if (c != 1.0f) {
            for (i in lum.indices) {
                lum[i] = (((lum[i] - 128) * c) + 128).roundToInt().coerceIn(0, 255)
            }
        }
        // 밝은 배경에서는 밝은 곳일수록 글자가 빽빽해야 사진처럼 보인다.
        if (settings.invert != settings.lightBackground) {
            for (i in lum.indices) lum[i] = 255 - lum[i]
        }
    }

    /** 회전된 좌표 (rx, ry) 에 대응하는 원본 픽셀의 휘도. */
    private fun lumaAt(p: YuvPlanes, rx: Int, ry: Int, rot: Int): Int {
        val sx = srcX(rx, ry, p.width, rot)
        val sy = srcY(rx, ry, p.height, rot)
        return p.y[sy * p.yRowStride + sx * p.yPixelStride].toInt() and 0xFF
    }

    /** 회전된 좌표 (rx, ry) 의 색을 불투명 ARGB 로. 어두운 색은 어느 정도 끌어올린다. */
    private fun colorAt(p: YuvPlanes, rx: Int, ry: Int, rot: Int): Int {
        val sx = srcX(rx, ry, p.width, rot)
        val sy = srcY(rx, ry, p.height, rot)
        val yv = p.y[sy * p.yRowStride + sx * p.yPixelStride].toInt() and 0xFF
        val ci = (sy / 2) * p.uvRowStride + (sx / 2) * p.uvPixelStride
        val u = p.u!!
        val v = p.v!!
        val cu = (if (ci < u.size) u[ci].toInt() and 0xFF else 128) - 128
        val cv = (if (ci < v.size) v[ci].toInt() and 0xFF else 128) - 128

        var r = (yv + 1.370705f * cv).roundToInt()
        var g = (yv - 0.337633f * cu - 0.698001f * cv).roundToInt()
        var b = (yv + 1.732446f * cu).roundToInt()
        r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)

        val m = max(r, max(g, b))
        if (m > 0) {
            val boost = min(255f / m, MAX_COLOR_BOOST)
            if (boost > 1f) {
                r = min(255, (r * boost).roundToInt())
                g = min(255, (g * boost).roundToInt())
                b = min(255, (b * boost).roundToInt())
            }
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // 회전은 원본을 시계방향으로 rot 도 돌린 것으로 본다.
    internal fun srcX(rx: Int, ry: Int, srcW: Int, rot: Int): Int = when (rot) {
        90 -> ry
        180 -> srcW - 1 - rx
        270 -> srcW - 1 - ry
        else -> rx
    }

    internal fun srcY(rx: Int, ry: Int, srcH: Int, rot: Int): Int = when (rot) {
        90 -> srcH - 1 - rx
        180 -> srcH - 1 - ry
        270 -> rx
        else -> ry
    }
}
