package com.ashwin.visionai

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Continuous LiDAR-based obstacle proximity monitoring for Walking
 * Assistant mode. Reconstructed from the manufacturer app's
 * WalkingAsstDistance.calculateDistance: distances are read in
 * millimetres and bucketed in 450mm (~1 step) increments up to 9 steps,
 * with an immediate "Stop!" warning plus a 200ms full-strength vibration
 * when very close. The exact threshold-to-message wiring couldn't be
 * traced with full certainty from decompiled bytecode alone, but every
 * number and message here (450mm step size, 9 step labels, the "Stop!"
 * text, and the VibrationEffect.createOneShot(200, 255) call) is taken
 * directly from their code, not guessed.
 */
class WalkingMonitor(
    private val context: Context,
    private val glassesClient: GlassesClient,
    private val onAnnouncement: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastStepBucket = -1
    @Volatile private var currentCall: okhttp3.Call? = null

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        lastStepBucket = -1
        thread = Thread {
            while (running.get()) {
                val call = glassesClient.lidarCall()
                currentCall = call
                if (call != null && running.get()) {
                    try {
                        call.execute().use { resp ->
                            val mm = glassesClient.parseLidarResponse(resp.body?.string())
                            if (mm != null && running.get()) handleDistance(mm)
                        }
                    } catch (e: Exception) {
                        // cancelled by stop(), or a network hiccup - either way just move on
                    }
                }
                currentCall = null
                if (!running.get()) break
                try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Stops polling immediately - cancels any in-flight LiDAR request right
     *  away rather than waiting for it to finish, so switching modes (via
     *  swipe or a button) exits Walking Assistant instantly, with no stray
     *  announcement or vibration from a request that was already underway. */
    fun stop() {
        if (!running.getAndSet(false)) return
        currentCall?.cancel()
        thread?.interrupt()
        thread = null
        // CRITICAL: stop() is called from UI-thread click/swipe handlers.
        // stopLidar() makes a blocking network call - calling it directly
        // here throws NetworkOnMainThreadException, which was being
        // silently swallowed inside stopLidar()'s own try/catch, meaning
        // the glasses never actually received the stop command and kept
        // beeping/sensing. Always dispatch it off the main thread.
        Thread { glassesClient.stopLidar() }.start()
    }

    private fun handleDistance(mm: Float) {
        if (mm < 450f) {
            vibrate()
            onAnnouncement("Stop! Turn to avoid the obstacle")
            lastStepBucket = 0
            return
        }
        val bucket = (mm / 450f).toInt().coerceIn(1, 9)
        if (bucket != lastStepBucket) {
            lastStepBucket = bucket
            if (bucket <= 3) vibrate()
            onAnnouncement("$bucket step${if (bucket > 1) "s" else ""} away")
        }
    }

    private fun vibrate() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, 255))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(200)
            }
        } catch (_: Exception) {}
    }
}
