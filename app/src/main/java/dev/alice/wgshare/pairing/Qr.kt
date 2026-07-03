package dev.alice.wgshare.pairing

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import dev.alice.wgshare.model.PairingPayload
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun PairingPayload.encode(): String = json.encodeToString(PairingPayload.serializer(), this)

fun decodePairing(text: String): PairingPayload? =
    runCatching { json.decodeFromString(PairingPayload.serializer(), text) }.getOrNull()

/** Renders [text] to a square QR [Bitmap] of [size] px. */
fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (x in 0 until size) for (y in 0 until size)
            setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
    }
}
