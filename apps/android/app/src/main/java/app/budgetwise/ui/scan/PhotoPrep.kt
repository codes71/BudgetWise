package app.budgetwise.ui.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import app.budgetwise.data.repo.ScanRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo ready to send: JPEG [bytes] within the server's limit, and the [bitmap] the screen shows behind its cards. */
class PreparedPhoto(val bytes: ByteArray, val bitmap: Bitmap) {
    val mediaType: String get() = "image/jpeg"
}

/**
 * Shrinks a photo for `POST /scan`: upright, at most [LONG_EDGE] px on the long edge (enough for receipt text), JPEG
 * at quality 90, lower only if a busy photo would still pass the server's 3 MB.
 */
object PhotoPrep {
    const val LONG_EDGE = 2000
    private val QUALITIES = intArrayOf(90, 80, 70, 60)

    fun prepare(bitmap: Bitmap, maxBytes: Int = ScanRepository.MAX_IMAGE_BYTES): PreparedPhoto {
        val scaled = scaleToLongEdge(bitmap, LONG_EDGE)
        for (quality in QUALITIES) {
            val bytes = jpeg(scaled, quality)
            if (bytes.size <= maxBytes) return PreparedPhoto(bytes, scaled)
        }
        // A 2000 px JPEG at quality 60 is far below 3 MB; halving once more is only a safety net.
        val smaller = scaleToLongEdge(scaled, LONG_EDGE / 2)
        return PreparedPhoto(jpeg(smaller, QUALITIES.last()), smaller)
    }

    fun scaleToLongEdge(bitmap: Bitmap, longEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= longEdge) return bitmap
        val ratio = longEdge.toFloat() / longest
        return bitmap.scale((bitmap.width * ratio).roundToInt(), (bitmap.height * ratio).roundToInt())
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    /** A CameraX capture, turned the way the phone was held. Closes [image]. */
    fun fromCapture(image: ImageProxy): Bitmap = image.use {
        rotate(it.toBitmap(), it.imageInfo.rotationDegrees)
    }

    /**
     * A photo chosen from the phone, upright, decoded at about [LONG_EDGE] px so a 50 MP picture never fills memory.
     * Null when it cannot be read as an image.
     */
    fun fromUri(context: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation itself.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > LONG_EDGE) {
                    val ratio = LONG_EDGE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * ratio).roundToInt(), (info.size.height * ratio).roundToInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeSampled(context, uri)?.let { rotate(it, exifRotation(context, uri)) }
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun exifRotation(context: Context, uri: Uri): Int =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
