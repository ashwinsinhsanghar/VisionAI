package com.ashwin.visionai

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.custom.CustomImageLabelerOptions
import java.util.concurrent.TimeUnit

/**
 * Wraps the user-supplied currency model using Google ML Kit's Custom
 * Image Labeling API - confirmed from the manufacturer app's decompiled
 * CurrencyOfflineDet class, which loads each country's model exactly this
 * way (InputImage.fromBitmap(...), ImageLabeler.process(...), reading
 * back ImageLabel.getText()/getConfidence()), not a raw TFLite Interpreter.
 * ML Kit reads the model's own embedded metadata for input size,
 * normalization and label names, so there's no manual preprocessing here
 * to get wrong.
 *
 * The real app also runs a binary "is this even a currency note" check and
 * a YOLO-based crop step before this classifier - those use additional
 * proprietary models this app doesn't have, so this classifier is applied
 * directly to the captured frame. For best accuracy, hold the note so it
 * fills most of the frame, since there's no automatic crop step here.
 */
class CurrencyClassifier private constructor(private val labeler: ImageLabeler) {

    data class Result(val label: String, val confidence: Float)

    /** Matches the real app's own threshold ("Accuracy less than 80%, recapturing"). */
    fun classify(bitmap: Bitmap): Result? {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val labels = Tasks.await(labeler.process(image), 10, TimeUnit.SECONDS)
            val best = labels.maxByOrNull { it.confidence } ?: return null
            Result(best.text, best.confidence)
        } catch (e: Exception) {
            android.util.Log.e("VisionAI", "Currency classify failed", e)
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "unknown")
            null
        }
    }

    fun close() = labeler.close()

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.8f

        /** Set whenever classify() or loadOrNull() fails, so the app can tell
         *  the person the real reason instead of a generic message - and so
         *  the next round of fixes is based on an actual error, not a guess. */
        @Volatile var lastError: String? = null
            private set

        fun loadOrNull(context: Context, modelAsset: String, @Suppress("UNUSED_PARAMETER") labelsAssetUnused: String): CurrencyClassifier? {
            return try {
                val localModel = com.google.mlkit.common.model.LocalModel.Builder()
                    .setAssetFilePath(modelAsset)
                    .build()
                val options = CustomImageLabelerOptions.Builder(localModel)
                    .setConfidenceThreshold(CONFIDENCE_THRESHOLD)
                    .setMaxResultCount(5)
                    .build()
                CurrencyClassifier(ImageLabeling.getClient(options))
            } catch (e: Exception) {
                android.util.Log.e("VisionAI", "Currency model load failed", e)
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "unknown")
                null
            }
        }
    }
}
