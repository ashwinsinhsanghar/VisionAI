package com.ashwin.visionai

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Builds the same kind of "WIFI:S:...;T:WPA;P:...;;" QR code the
 * manufacturer's app shows for the glasses' camera to scan during
 * pairing. Generated fully offline - no network call involved.
 */
object QrUtil {
    fun wifiQrBitmap(ssid: String, password: String, size: Int = 600): Bitmap? {
        // Field order matches the manufacturer app's Gen5Connection.generateWiFiQRCode
        // exactly (T before S) - the glasses' own QR parser is order-sensitive.
        val content = "WIFI:T:WPA;S:$ssid;P:$password;;"
        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
