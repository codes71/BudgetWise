package app.budgetwise.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoPrepTest {
    private val random = Random(7)

    /** [size] pixels of grey [luma], with [share] of them at [otherLuma] instead (text, paper or a screen). */
    private fun pixels(luma: Int, otherLuma: Int = luma, share: Double = 0.0, size: Int = 128 * 96, noise: Int = 0) =
        IntArray(size) {
            val y = (if (random.nextDouble() < share) otherLuma else luma) + if (noise > 0) random.nextInt(noise) else 0
            Color.rgb(y.coerceIn(0, 255), y.coerceIn(0, 255), y.coerceIn(0, 255))
        }

    @Test
    fun onlyAPhotoWithNoBrightPartIsTooDark() {
        assertTrue("a covered lens", PhotoPrep.tooDark(pixels(0)))
        assertTrue("a dark room's sensor noise", PhotoPrep.tooDark(pixels(4, noise = 24)))
        // A dim shop: grey paper, black print.
        assertFalse(PhotoPrep.tooDark(pixels(90, otherLuma = 20, share = 0.15, noise = 10)))
        // Mostly black around a lit computer screen.
        assertFalse(PhotoPrep.tooDark(pixels(8, otherLuma = 180, share = 0.2)))
        // A dark-mode screenshot (only camera photos are checked, but it would pass too): thin light text on near black.
        assertFalse(PhotoPrep.tooDark(pixels(18, otherLuma = 220, share = 0.05)))
        assertFalse(PhotoPrep.tooDark(IntArray(0)))
    }

    @Test
    fun aScrollingScreenshotIsTooLongAndAPhoneScreenIsNot() {
        assertTrue(PhotoPrep.tooLong(1080, 7000))
        assertTrue(PhotoPrep.tooLong(8000, 1080))
        assertFalse("a 20:9 screenshot", PhotoPrep.tooLong(1080, 2400))
        assertFalse("a 21:9 screenshot", PhotoPrep.tooLong(1080, 2520))
        assertFalse("a camera photo", PhotoPrep.tooLong(2560, 1920))
        assertFalse(PhotoPrep.tooLong(0, 0))
    }

    @Test
    fun aTransparentPictureIsSentOnWhiteNotBlack() {
        val png = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        png.eraseColor(Color.TRANSPARENT)
        for (x in 0 until 10) png.setPixel(x, 20, Color.BLACK)

        val prepared = PhotoPrep.prepare(png)

        assertFalse(prepared.bitmap.hasAlpha())
        assertEquals("the screen shows what is sent", Color.WHITE, prepared.bitmap.getPixel(30, 5))
        val sent = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)
        assertTrue("the background is white in the JPEG", Color.red(sent.getPixel(30, 5)) > 240)
        assertTrue("the text stays dark", Color.red(sent.getPixel(5, 20)) < 80)
    }

    @Test
    fun aDarkCameraPhotoIsJudgedFromASmallCopy() {
        val black = Bitmap.createBitmap(2560, 1920, Bitmap.Config.ARGB_8888)
        black.eraseColor(Color.BLACK)
        assertTrue(PhotoPrep.tooDark(black))

        val receipt = Bitmap.createBitmap(2560, 1920, Bitmap.Config.ARGB_8888)
        receipt.eraseColor(Color.rgb(200, 195, 185))
        assertFalse(PhotoPrep.tooDark(receipt))
    }
}
