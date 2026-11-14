package com.hayoonjae.asciicam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsciiConverterTest {

    /** 회전 없이 픽셀 값을 그대로 쓰는 단색 평면. */
    private fun planes(w: Int, h: Int, luma: (Int, Int) -> Int): YuvPlanes {
        val y = ByteArray(w * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                y[row * w + col] = luma(col, row).coerceIn(0, 255).toByte()
            }
        }
        return YuvPlanes(w, h, y, w, 1, null, null, 0, 1)
    }

    private fun grid(cols: Int, rows: Int) = Grid(cols, rows, cols.toFloat() / rows.toFloat())

    @Test
    fun `어두운 화면은 램프의 첫 글자로 채워진다`() {
        val frame = AsciiConverter.convert(
            planes(64, 64) { _, _ -> 0 },
            rotationDegrees = 0,
            mirror = false,
            grid = grid(8, 8),
            settings = Settings(autoLevels = false, contrast = 1f),
        )
        assertEquals(8 * 8, frame.chars.size)
        assertTrue(frame.chars.all { it == Ramp.CLASSIC.chars.first() })
        assertNull("색 모드가 아니면 색 배열을 만들지 않는다", frame.colors)
    }

    @Test
    fun `밝은 화면은 램프의 마지막 글자로 채워진다`() {
        val frame = AsciiConverter.convert(
            planes(64, 64) { _, _ -> 255 },
            rotationDegrees = 0,
            mirror = false,
            grid = grid(8, 8),
            settings = Settings(autoLevels = false, contrast = 1f),
        )
        assertTrue(frame.chars.all { it == Ramp.CLASSIC.chars.last() })
    }

    @Test
    fun `가로 밝기 그라디언트는 왼쪽에서 오른쪽으로 촘촘해진다`() {
        val ramp = Ramp.CLASSIC.chars
        val frame = AsciiConverter.convert(
            planes(160, 160) { x, _ -> x * 255 / 159 },
            rotationDegrees = 0,
            mirror = false,
            grid = grid(10, 10),
            settings = Settings(autoLevels = false, contrast = 1f),
        )
        val row = (0 until 10).map { ramp.indexOf(frame.chars[it]) }
        assertEquals(row.sorted(), row)
        assertTrue("양 끝이 구분되어야 한다", row.last() > row.first())
    }

    @Test
    fun `미러는 각 행을 좌우로 뒤집는다`() {
        val src = planes(160, 160) { x, _ -> x * 255 / 159 }
        val settings = Settings(autoLevels = false, contrast = 1f)
        val normal = AsciiConverter.convert(src, 0, false, grid(10, 10), settings)
        val mirrored = AsciiConverter.convert(src, 0, true, grid(10, 10), settings)
        for (i in 0 until 10) {
            assertEquals(normal.chars[i], mirrored.chars[9 - i])
        }
    }

    @Test
    fun `90도 회전은 가로 그라디언트를 세로로 바꾼다`() {
        val ramp = Ramp.CLASSIC.chars
        // 원본에서 x 가 커질수록 밝다. 시계방향 90도면 오른쪽 열이 아래쪽 행으로 가므로 아래가 밝아진다.
        val src = planes(160, 160) { x, _ -> x * 255 / 159 }
        val f = AsciiConverter.convert(src, 90, false, grid(10, 10), Settings(autoLevels = false, contrast = 1f))
        val col = (0 until 10).map { r -> ramp.indexOf(f.chars[r * 10]) }
        assertEquals(col.sorted(), col)
        // 같은 행 안에서는 밝기가 같아야 한다.
        for (r in 0 until 10) {
            val rowChars = (0 until 10).map { f.chars[r * 10 + it] }.toSet()
            assertEquals(1, rowChars.size)
        }
    }

    @Test
    fun `회전 좌표 변환은 네 모서리를 제자리로 보낸다`() {
        val w = 7
        val h = 5
        // 90도: 결과의 좌상단은 원본의 좌하단에서 온다.
        assertEquals(0, AsciiConverter.srcX(0, 0, w, 90))
        assertEquals(h - 1, AsciiConverter.srcY(0, 0, h, 90))
        // 270도: 결과의 좌상단은 원본의 우상단에서 온다.
        assertEquals(w - 1, AsciiConverter.srcX(0, 0, w, 270))
        assertEquals(0, AsciiConverter.srcY(0, 0, h, 270))
        // 180도: 결과의 좌상단은 원본의 우하단에서 온다.
        assertEquals(w - 1, AsciiConverter.srcX(0, 0, w, 180))
        assertEquals(h - 1, AsciiConverter.srcY(0, 0, h, 180))
    }

    @Test
    fun `자동 보정은 좁은 밝기 범위를 끝까지 펴 준다`() {
        val lum = intArrayOf(100, 120, 140, 160)
        AsciiConverter.applyLevels(lum, Settings(autoLevels = true, contrast = 1f))
        assertEquals(0, lum.first())
        assertEquals(255, lum.last())
    }

    @Test
    fun `자동 보정은 명암 폭이 거의 없으면 노이즈를 키우지 않는다`() {
        val lum = intArrayOf(128, 129, 130)
        AsciiConverter.applyLevels(lum, Settings(autoLevels = true, contrast = 1f))
        assertEquals(listOf(128, 129, 130), lum.toList())
    }

    @Test
    fun `반전은 밝기를 뒤집는다`() {
        val lum = intArrayOf(0, 64, 255)
        AsciiConverter.applyLevels(lum, Settings(autoLevels = false, contrast = 1f, invert = true))
        assertEquals(listOf(255, 191, 0), lum.toList())
    }

    @Test
    fun `밝은 배경과 반전을 같이 켜면 서로 상쇄된다`() {
        val lum = intArrayOf(0, 64, 255)
        AsciiConverter.applyLevels(
            lum,
            Settings(autoLevels = false, contrast = 1f, invert = true, lightBackground = true),
        )
        assertEquals(listOf(0, 64, 255), lum.toList())
    }

    @Test
    fun `대비를 올리면 중간값에서 더 멀어진다`() {
        val lum = intArrayOf(64, 192)
        AsciiConverter.applyLevels(lum, Settings(autoLevels = false, contrast = 2f))
        assertEquals(listOf(0, 255), lum.toList())
    }

    @Test
    fun `카메라 색 모드는 셀마다 색을 채운다`() {
        val w = 64
        val h = 64
        val y = ByteArray(w * h) { 128.toByte() }
        val cw = w / 2
        val ch = h / 2
        // U 를 낮춰 붉은 기가 도는 색을 만든다.
        val u = ByteArray(cw * ch) { 40.toByte() }
        val v = ByteArray(cw * ch) { 200.toByte() }
        val planes = YuvPlanes(w, h, y, w, 1, u, v, cw, 1)

        val frame = AsciiConverter.convert(
            planes, 0, false, grid(8, 8),
            Settings(colorMode = ColorMode.CAMERA, autoLevels = false, contrast = 1f),
        )
        val colors = frame.colors
        assertTrue("색 모드에서는 색 배열이 있어야 한다", colors != null)
        val c = colors!![0]
        assertEquals("불투명이어야 한다", 0xFF, (c ushr 24) and 0xFF)
        val r = (c shr 16) and 0xFF
        val b = c and 0xFF
        assertTrue("V 가 높고 U 가 낮으면 빨강이 파랑보다 세다", r > b)
        assertTrue(colors.all { it == c })
    }

    @Test
    fun `격자 비율이 원본과 다르면 늘리지 않고 가운데를 잘라 쓴다`() {
        // 가로로 긴 원본에서 가운데 정사각형만 밝게 둔다.
        val src = planes(160, 80) { x, _ -> if (x in 40..119) 255 else 0 }
        val frame = AsciiConverter.convert(
            src, 0, false,
            Grid(8, 8, 1.0f),
            Settings(autoLevels = false, contrast = 1f),
        )
        // 정사각형으로 크롭했으니 전부 밝은 영역에서 왔어야 한다.
        assertTrue(frame.chars.all { it == Ramp.CLASSIC.chars.last() })

        // 반대로 원본 비율 그대로 보면 양옆의 어두운 부분이 들어온다.
        val wide = AsciiConverter.convert(
            src, 0, false,
            Grid(8, 8, 2.0f),
            Settings(autoLevels = false, contrast = 1f),
        )
        assertNotEquals(Ramp.CLASSIC.chars.last(), wide.chars[0])
    }

    @Test
    fun `텍스트 변환은 줄바꿈으로 행을 나눈다`() {
        val frame = AsciiFrame(3, 2, charArrayOf('a', 'b', 'c', 'd', 'e', 'f'), null)
        assertEquals("abc\ndef\n", frame.toText())
    }
}
