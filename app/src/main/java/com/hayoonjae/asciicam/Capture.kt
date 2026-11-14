package com.hayoonjae.asciicam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Capture {

    private val ALBUM = Environment.DIRECTORY_PICTURES + "/AsciiCam"

    /** 저장 폭. 글자가 또렷하게 남을 정도로 크게 잡는다. */
    const val SAVE_WIDTH = 2160

    /** 갤러리(Pictures/AsciiCam)에 PNG 로 저장하고 표시할 이름을 돌려준다. */
    fun savePng(context: Context, frame: AsciiFrame, settings: Settings): String {
        val name = "ascii-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("갤러리에 파일을 만들지 못했습니다")

        var bitmap: Bitmap? = null
        try {
            bitmap = AsciiRenderer.renderToBitmap(frame, settings, SAVE_WIDTH)
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: error("갤러리에 쓸 수 없습니다")
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        } finally {
            bitmap?.recycle()
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return name
    }
}
