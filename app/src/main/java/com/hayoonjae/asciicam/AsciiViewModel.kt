package com.hayoonjae.asciicam

import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.nio.ByteBuffer

class AsciiViewModel : ViewModel() {

    var settings by mutableStateOf(Settings())
        private set

    var frame by mutableStateOf<AsciiFrame?>(null)
        private set

    var fps by mutableStateOf(0)
        private set

    /** 분석 스레드가 읽는 사본. Compose 상태를 다른 스레드에서 읽지 않으려고 따로 둔다. */
    @Volatile
    private var analysisSettings = settings

    /** 화면을 실제로 재서 얻은 격자. 분석 스레드가 읽는다. */
    @Volatile
    private var grid = Grid(90, 120, 0.5f)

    private var yBuf = ByteArray(0)
    private var uBuf = ByteArray(0)
    private var vBuf = ByteArray(0)

    private var frameCount = 0
    private var fpsWindowStart = 0L

    fun update(transform: (Settings) -> Settings) {
        settings = transform(settings)
        analysisSettings = settings
    }

    fun setGrid(g: Grid) {
        grid = g
    }

    /** 분석 스레드에서 호출된다. 프레임을 닫는 건 호출자 책임. */
    fun analyze(image: ImageProxy) {
        val s = analysisSettings
        val g = grid
        val planes = readPlanes(image, wantColor = s.colorMode == ColorMode.CAMERA) ?: return
        val result = AsciiConverter.convert(
            planes = planes,
            rotationDegrees = image.imageInfo.rotationDegrees,
            mirror = s.frontCamera,
            grid = g,
            settings = s,
        )
        val now = System.nanoTime()
        frameCount++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        val elapsed = now - fpsWindowStart
        val measured = if (elapsed >= 1_000_000_000L) {
            val v = (frameCount * 1_000_000_000L / elapsed).toInt()
            frameCount = 0
            fpsWindowStart = now
            v
        } else {
            null
        }
        publish(result, measured)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun publish(result: AsciiFrame, measured: Int?) {
        // Compose 상태는 메인 스레드에서만 건드린다.
        mainHandler.post {
            frame = result
            if (measured != null) fps = measured
        }
    }

    /** ImageProxy 의 평면을 재사용 가능한 배열로 복사한다. */
    private fun readPlanes(image: ImageProxy, wantColor: Boolean): YuvPlanes? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val yp = planes[0]
        yBuf = copyInto(yp.buffer, yBuf)

        var u: ByteArray? = null
        var v: ByteArray? = null
        var uvRowStride = 0
        var uvPixelStride = 1
        if (wantColor && planes.size >= 3) {
            uBuf = copyInto(planes[1].buffer, uBuf)
            vBuf = copyInto(planes[2].buffer, vBuf)
            u = uBuf
            v = vBuf
            uvRowStride = planes[1].rowStride
            uvPixelStride = planes[1].pixelStride
        }
        return YuvPlanes(
            width = image.width,
            height = image.height,
            y = yBuf,
            yRowStride = yp.rowStride,
            yPixelStride = yp.pixelStride,
            u = u,
            v = v,
            uvRowStride = uvRowStride,
            uvPixelStride = uvPixelStride,
        )
    }

    private fun copyInto(src: ByteBuffer, reuse: ByteArray): ByteArray {
        src.rewind()
        val n = src.remaining()
        val out = if (reuse.size == n) reuse else ByteArray(n)
        src.get(out)
        return out
    }
}
