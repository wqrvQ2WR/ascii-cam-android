package com.hayoonjae.asciicam

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity

/**
 * ASCII 격자를 화면 가득 그린다. 화면을 실제로 재서 얻은 격자 크기를 [onGrid] 로 돌려주면
 * 분석 스레드가 그 크기에 맞춰 다음 프레임을 만든다.
 */
@Composable
fun AsciiView(
    frame: AsciiFrame?,
    settings: Settings,
    onGrid: (Grid) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val metrics = remember(widthPx, settings.cols) {
            AsciiRenderer.metricsFor(widthPx, settings.cols)
        }
        val rows = remember(heightPx, metrics) { AsciiRenderer.rowsFor(heightPx, metrics) }

        LaunchedEffect(settings.cols, rows, metrics) {
            val aspect = (settings.cols * metrics.cellW) / (rows * metrics.cellH)
            onGrid(Grid(settings.cols, rows, aspect))
        }

        val glyphPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
        val blendPaint = remember { Paint() }
        val colorBitmap = remember(frame?.cols, frame?.rows, settings.colorMode) {
            val f = frame
            if (settings.colorMode == ColorMode.CAMERA && f != null) {
                Bitmap.createBitmap(f.cols, f.rows, Bitmap.Config.ARGB_8888)
            } else {
                null
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val f = frame
                // 글자 폭은 cols 로 정해지므로, 설정을 막 바꿔 아직 이전 격자인 프레임은 건너뛴다.
                if (f == null || f.cols != settings.cols) {
                    native.drawColor(
                        if (settings.lightBackground) AsciiRenderer.LIGHT_BG else AsciiRenderer.DARK_BG
                    )
                    return@drawIntoCanvas
                }
                AsciiRenderer.draw(
                    canvas = native,
                    frame = f,
                    m = metrics,
                    settings = settings,
                    glyphPaint = glyphPaint,
                    blendPaint = blendPaint,
                    colorBitmap = colorBitmap?.takeIf { it.width == f.cols && it.height == f.rows },
                )
            }
        }
    }
}
