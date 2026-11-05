package com.ashwin.visionai

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Talks to the glasses' own built-in HTTP server (capture.cgi / battery-percent.cgi /
 * config-camera.cgi), the same endpoints the manufacturer's app uses. We never saw an
 * auth header or token on these calls while reading the original app's code, so none
 * is sent here. If your glasses' firmware ever requires one, add it in a single place:
 * the `request()` builder below.
 */
class GlassesClient(private val ipProvider: () -> String?) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private fun base(): String? = ipProvider()?.let { "http://$it" }

    private fun request(path: String): Request.Builder? {
        val b = base() ?: return null
        return Request.Builder().url(b + path)
    }

    /** One JPEG frame from the glasses' camera, matching the exact URL the
     *  manufacturer app's GetImage.getFrame() uses. */
    private fun rawCapture(): ByteArray? {
        val path = "/capture.cgi?flash-intensity=0&turn-off-camera=no&silent=1&t=${System.currentTimeMillis()}"
        val req = request(path)?.build() ?: return null
        return try {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** For Capture Mode: sets the glasses to their highest resolution first,
     *  then returns the RAW JPEG bytes exactly as the camera sent them -
     *  nothing here re-encodes or recompresses the image, so whatever
     *  quality the glasses' camera is capable of is what you get. */
    fun captureHighRes(): ByteArray? {
        setResolution("2560x1440")
        Thread.sleep(400) // let the sensor apply the new resolution before shooting
        rawCapture() // throwaway warm-up frame, same reasoning as captureFrame()
        Thread.sleep(300)
        return rawCapture()
    }

    /** NOTE on accuracy: I could not confirm from the decompiled code alone
     *  whether a single capture.cgi call always returns a genuinely fresh
     *  frame, or can occasionally return a stale/buffered one if the sensor
     *  was idle. If results seem to lag what's actually in front of the
     *  camera, this throws away one frame and waits briefly before taking
     *  the one actually used - a practical mitigation, not a confirmed
     *  root-cause fix. Remove the warm-up if it turns out unnecessary. */
    fun captureFrame(resolution: String? = null): ByteArray? {
        rawCapture()
        Thread.sleep(300)
        return rawCapture()
    }

    fun setResolution(resolution: String) {
        val req = request(
            "/config-camera.cgi?resolution=$resolution&sharpness=4&shutterspeed=auto" +
                "&brightness=5&contrast=6&saturation=9&t=${System.currentTimeMillis()}"
        )?.build() ?: return
        try { http.newCall(req).execute().close() } catch (_: Exception) {}
    }

    fun batteryPercent(): Int? {
        val req = request("/battery-percent.cgi?t=${System.currentTimeMillis()}")?.build() ?: return null
        return try {
            http.newCall(req).execute().use { r -> r.body?.string()?.trim()?.filter { it.isDigit() }?.toIntOrNull() }
        } catch (e: Exception) { null }
    }

    /** Builds (but doesn't execute) the LiDAR request as a cancellable Call,
     *  so a caller like WalkingMonitor can abort it instantly on stop()
     *  instead of waiting for an in-flight network request to finish. */
    fun lidarCall(): okhttp3.Call? {
        val req = request("/lidar.cgi?t=${System.currentTimeMillis()}")?.build() ?: return null
        return http.newCall(req)
    }

    fun parseLidarResponse(body: String?): Float? {
        if (body == null) return null
        return try { org.json.JSONObject(body).optString("lidar").toFloatOrNull() } catch (e: Exception) { null }
    }

    /** Confirmed endpoint: GET /lidar.cgi?action=stop */
    fun stopLidar() {
        val req = request("/lidar.cgi?action=stop")?.build() ?: return
        try { http.newCall(req).execute().close() } catch (_: Exception) {}
    }

    fun turnOffCamera() {
        val req = request("/capture.cgi?turn-off-camera-only=yes&t=${System.currentTimeMillis()}")?.build() ?: return
        try { http.newCall(req).execute().close() } catch (_: Exception) {}
    }
}
