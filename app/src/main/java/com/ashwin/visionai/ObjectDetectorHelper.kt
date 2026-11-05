package com.ashwin.visionai

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.io.File
import java.net.URL

/**
 * Walking-mode obstacle/object detection using Google's official, openly
 * licensed MediaPipe EfficientDet-Lite0 model (80 COCO classes: person,
 * chair, dog, car, etc.) - not anything extracted from a third-party app.
 *
 * Downloaded once from Google's own hosting (not bundled in the APK, to
 * keep it small enough for the Telegram upload step) and cached in the
 * app's own storage after that - so it only ever needs internet the one
 * time, ideally triggered from Settings before heading out with the
 * glasses, rather than silently in the background on first launch.
 */
class ObjectDetectorHelper private constructor(private val detector: ObjectDetector) {

    data class Detection(val label: String, val confidence: Float, val box: android.graphics.RectF)

    fun detect(bitmap: Bitmap): List<Detection> {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result: ObjectDetectorResult = detector.detect(mpImage)
        return result.detections().map { d ->
            val cat = d.categories().firstOrNull()
            Detection(
                label = cat?.categoryName() ?: "object",
                confidence = cat?.score() ?: 0f,
                box = d.boundingBox()
            )
        }
    }

    fun close() = detector.close()

    companion object {
        private const val MODEL_FILE_NAME = "efficientdet_lite0.tflite"

        // Official Google-hosted model, documented at:
        // https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector
        private const val OFFICIAL_MODEL_URL =
            "https://storage.googleapis.com/mediapipe-tasks/object_detector/efficientdet_lite0_uint8.tflite"

        private fun modelFile(context: Context) = File(context.filesDir, MODEL_FILE_NAME)

        /** True once the model has been downloaded and cached - after that,
         *  Walking Assistant never needs internet again. */
        fun isModelDownloaded(context: Context): Boolean {
            val f = modelFile(context)
            return f.exists() && f.length() > 0
        }

        /** Call this off the main thread. Downloads only if not already
         *  cached; returns true on success (or if it was already there). */
        fun downloadModel(context: Context): Boolean {
            val out = modelFile(context)
            if (out.exists() && out.length() > 0) return true
            return try {
                URL(OFFICIAL_MODEL_URL).openStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } catch (e: Exception) {
                out.delete()
                false
            }
        }

        /** Set whenever loadOrNull() fails, so the real reason can be spoken
         *  back instead of guessed at next time. */
        @Volatile var lastError: String? = null
            private set

        /** Call this off the main thread. Returns null if the model hasn't
         *  been downloaded yet (use downloadModel() first) or fails to load. */
        fun loadOrNull(context: Context): ObjectDetectorHelper? {
            if (!isModelDownloaded(context)) return null
            return try {
                val baseOptions = BaseOptions.builder().setModelAssetPath(modelFile(context).absolutePath).build()
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setScoreThreshold(0.5f)
                    .setMaxResults(5)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()
                ObjectDetectorHelper(ObjectDetector.createFromOptions(context, options))
            } catch (e: Exception) {
                android.util.Log.e("VisionAI", "Walking detector load failed", e)
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "unknown")
                null
            }
        }
    }
}
