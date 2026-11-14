package com.hayoonjae.asciicam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.floor

/** 고정폭 글자 한 칸의 크기. 격자 배치와 글꼴 크기를 여기서 한 번만 계산한다. */
class CellMetrics(val cellW: Float, val cellH: Float, val textSize: Float, val ascent: Float)

object AsciiRenderer {

    /** 어두운 배경 / 밝은 배경에서 쓰는 바탕색. */
    const val DARK_BG = 0xFF0B0B10.toInt()
    const val LIGHT_BG = 0xFFFDFBF7.toInt()
    const val DARK_INK = 0xFF16161D.toInt()
    const val LIGHT_INK = 0xFFFFFFFF.toInt()

    private val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 100f
    }

    /** 글자 폭이 정확히 [widthPx]/[cols] 가 되는 글꼴 크기를 역산한다. */
    @Synchronized
    fun metricsFor(widthPx: Float, cols: Int): CellMetrics {
        val advancePerUnit = probe.measureText("M") / probe.textSize
        val cellW = widthPx / cols.coerceAtLeast(1)
        val textSize = cellW / advancePerUnit
        probe.textSize = textSize
        val fm = probe.fontMetrics
        probe.textSize = 100f
        return CellMetrics(cellW, fm.descent - fm.ascent, textSize, fm.ascent)
    }

    fun rowsFor(heightPx: Float, m: CellMetrics): Int =
        floor(heightPx / m.cellH).toInt().coerceAtLeast(1)

    /**
     * [frame] 을 [canvas] 에 그린다. 배경도 여기서 칠한다.
     * [colorBitmap] 은 카메라 색 모드에서만 쓰이며 frame 과 같은 cols x rows 여야 한다.
     * 재사용을 위해 호출자가 들고 있다가 넘겨 준다.
     */
    fun draw(
        canvas: Canvas,
        frame: AsciiFrame,
        m: CellMetrics,
        settings: Settings,
        glyphPaint: Paint,
        blendPaint: Paint,
        colorBitmap: Bitmap?,
    ) {
        val bg = if (settings.lightBackground) LIGHT_BG else DARK_BG
        canvas.drawColor(bg)

        val w = frame.cols * m.cellW
        val h = frame.rows * m.cellH

        glyphPaint.typeface = Typeface.MONOSPACE
        glyphPaint.textSize = m.textSize
        glyphPaint.isAntiAlias = true

        val colors = frame.colors
        val useCameraColor = settings.colorMode == ColorMode.CAMERA && colors != null && colorBitmap != null

        // 카메라 색은 글자를 한 겹에 흰색으로 찍고 그 위에 색 비트맵을 SRC_IN 으로 덮는다.
        // 글자마다 drawText 를 부르는 것보다 훨씬 싸다.
        val layer = if (useCameraColor) canvas.saveLayer(0f, 0f, w, h, null) else -1

        glyphPaint.shader = when {
            useCameraColor -> null
            settings.colorMode == ColorMode.GRADIENT -> LinearGradient(
                0f, 0f, w, h,
                settings.gradient.start, settings.gradient.end,
                Shader.TileMode.CLAMP,
            )
            else -> null
        }
        glyphPaint.color = when {
            useCameraColor -> LIGHT_INK
            settings.colorMode == ColorMode.MONO && settings.lightBackground -> DARK_INK
            settings.colorMode == ColorMode.MONO -> LIGHT_INK
            else -> LIGHT_INK
        }

        for (r in 0 until frame.rows) {
            val baseline = r * m.cellH - m.ascent
            canvas.drawText(frame.chars, r * frame.cols, frame.cols, 0f, baseline, glyphPaint)
        }

        if (useCameraColor) {
            colorBitmap.setPixels(colors, 0, frame.cols, 0, 0, frame.cols, frame.rows)
            blendPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            blendPaint.isFilterBitmap = true
            canvas.drawBitmap(colorBitmap, null, RectF(0f, 0f, w, h), blendPaint)
            blendPaint.xfermode = null
            canvas.restoreToCount(layer)
        }
        glyphPaint.shader = null
    }

    /** 저장용으로 프레임을 [widthPx] 폭의 비트맵에 다시 그린다. */
    fun renderToBitmap(frame: AsciiFrame, settings: Settings, widthPx: Int): Bitmap {
        val m = metricsFor(widthPx.toFloat(), frame.cols)
        val h = (frame.rows * m.cellH).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val colorBmp = if (settings.colorMode == ColorMode.CAMERA && frame.colors != null) {
            Bitmap.createBitmap(frame.cols, frame.rows, Bitmap.Config.ARGB_8888)
        } else {
            null
        }
        draw(Canvas(bmp), frame, m, settings, Paint(Paint.ANTI_ALIAS_FLAG), Paint(), colorBmp)
        colorBmp?.recycle()
        return bmp
    }
}
