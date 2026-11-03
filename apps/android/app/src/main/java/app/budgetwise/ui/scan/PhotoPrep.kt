package app.budgetwise.ui.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.budgetwise.data.repo.ScanRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A photo ready to send: JPEG [bytes] within the server's limit, and the [bitmap] the screen shows behind its cards. */
class PreparedPhoto(val bytes: ByteArray, val bitmap: Bitmap) {
    val mediaType: String get() = "image/jpeg"
}

/** What a photo chosen from the phone turned out to be. */
sealed interface OpenedPhoto {
    /** Upright, at about [PhotoPrep.LONG_EDGE] px. */
    class Photo(val bitmap: Bitmap) : OpenedPhoto

    /** Far taller (or wider) than a phone screen, such as a scrolling screenshot: see [PhotoPrep.tooLong]. */
    data object TooLong : OpenedPhoto

    /** Not an image this phone can open: a corrupt file, a cloud photo while offline, HEIC before Android 9. */
    data object CannotOpen : OpenedPhoto
}

/**
 * Shrinks a photo for `POST /scan`: upright, at most [LONG_EDGE] px on the long edge (enough for receipt text), JPEG
 * at quality 90, lower only if a busy photo would still pass the server's 3 MB.
 */
object PhotoPrep {
    const val LONG_EDGE = 2000

    /**
     * The longest a picture may be for its width. Phone screens are up to about 2.4 times as tall as wide (21:9), and
     * camera photos 4:3. A scrolling screenshot of 1080 x 8000 would come out about 270 px wide at [LONG_EDGE], and the
     * AI shrinks it further by its long edge, so its text could not be read.
     */
    const val MAX_ASPECT = 2.6f

    /** In the 0 to 255 luma of [tooDark]: below this, even the brightest part of a photo is too dark for text. */
    private const val DARK_LUMA = 40

    private val QUALITIES = intArrayOf(90, 80, 70, 60)

    /** Throws IOException when the photo cannot be turned into a JPEG. */
    fun prepare(bitmap: Bitmap, maxBytes: Int = ScanRepository.MAX_IMAGE_BYTES): PreparedPhoto {
        // Once, before the quality loop: the screen shows the same flattened picture the server gets.
        val scaled = opaque(scaleToLongEdge(bitmap, LONG_EDGE))
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
        return bitmap.scale((bitmap.width * ratio).roundToInt().coerceAtLeast(1), (bitmap.height * ratio).roundToInt().coerceAtLeast(1))
    }

    /**
     * [bitmap] on white when it has transparency. A JPEG has none: a PNG receipt's see-through background would
     * otherwise come out black, under its black text.
     */
    fun opaque(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = createBitmap(bitmap.width, bitmap.height)
        flat.eraseColor(Color.WHITE)
        Canvas(flat).drawBitmap(bitmap, 0f, 0f, null)
        flat.setHasAlpha(false)
        return flat
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            // An empty photo would otherwise pass the size check and be sent.
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) || out.size() == 0) {
                throw IOException("the photo could not be encoded")
            }
            out.toByteArray()
        }

    /** A picture of [width] x [height] whose long edge is more than [MAX_ASPECT] times its short one. */
    fun tooLong(width: Int, height: Int): Boolean {
        val short = min(width, height)
        return short > 0 && max(width, height).toFloat() / short > MAX_ASPECT
    }

    /**
     * A camera photo with no bright part at all: a covered lens, or a room too dark for any paper to show. Judged by
     * the brightest 1% of a small copy, not the average, so a dim photo that still shows a receipt passes. Only for the
     * camera: a dark-mode KPay screenshot is mostly near black and is fine.
     */
    fun tooDark(bitmap: Bitmap): Boolean {
        val thumb = scaleToLongEdge(bitmap, 128)
        val pixels = IntArray(thumb.width * thumb.height)
        thumb.getPixels(pixels, 0, thumb.width, 0, 0, thumb.width, thumb.height)
        if (thumb !== bitmap) thumb.recycle()
        return tooDark(pixels)
    }

    /** [tooDark] for [pixels] as ARGB ints. */
    internal fun tooDark(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        val counts = IntArray(256)
        for (p in pixels) {
            val luma = (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
            counts[luma]++
        }
        // The luma the brightest 1% of the pixels reach.
        var brighter = 0
        var luma = 255
        while (luma > 0) {
            brighter += counts[luma]
            if (brighter * 100 >= pixels.size) break
            luma--
        }
        return luma < DARK_LUMA
    }

    /** A CameraX capture, turned the way the phone was held. Closes [image]. */
    fun fromCapture(image: ImageProxy): Bitmap = image.use {
        rotate(it.toBitmap(), it.imageInfo.rotationDegrees)
    }

    /**
     * A photo chosen from the phone, upright, decoded at about [LONG_EDGE] px so a 50 MP picture never fills memory. A
     * picture [tooLong] to read is not decoded in full.
     */
    fun open(context: Context, uri: Uri): OpenedPhoto = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            var long = false
            // ImageDecoder applies the EXIF orientation itself.
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (tooLong(info.size.width, info.size.height)) {
                    // Only its size was wanted: the smallest decode, never shown or sent.
                    long = true
                    decoder.setTargetSize(1, 1)
                } else if (longest > LONG_EDGE) {
                    val ratio = LONG_EDGE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * ratio).roundToInt(), (info.size.height * ratio).roundToInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            if (long) OpenedPhoto.TooLong else OpenedPhoto.Photo(bitmap)
        } else {
            openSampled(context, uri)
        }
    } catch (_: IOException) {
        OpenedPhoto.CannotOpen
    } catch (_: IllegalArgumentException) {
        OpenedPhoto.CannotOpen
    } catch (_: SecurityException) {
        OpenedPhoto.CannotOpen
    }

    private fun openSampled(context: Context, uri: Uri): OpenedPhoto {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return OpenedPhoto.CannotOpen
        if (tooLong(bounds.outWidth, bounds.outHeight)) return OpenedPhoto.TooLong
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return OpenedPhoto.CannotOpen
        return OpenedPhoto.Photo(rotate(bitmap, exifRotation(context, uri)))
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
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        // Only the turned copy is used: free the full-size original now, not at the next collection.
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}
