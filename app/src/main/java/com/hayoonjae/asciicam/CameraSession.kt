package com.hayoonjae.asciicam

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService

/**
 * ImageAnalysis 만 바인딩한다. 화면에 보이는 건 ASCII 격자뿐이라 Preview 유스케이스가 필요 없다.
 */
object CameraSession {

    /** 분석 해상도. ASCII 격자가 기껏해야 수백 칸이라 이 이상은 낭비다. */
    private val TARGET = Size(640, 480)

    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        executor: ExecutorService,
        front: Boolean,
        onFrame: (androidx.camera.core.ImageProxy) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    TARGET,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(executor) { image ->
                    try {
                        onFrame(image)
                    } finally {
                        image.close()
                    }
                }

                val selector = if (front) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, analysis)
            } catch (t: Throwable) {
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
