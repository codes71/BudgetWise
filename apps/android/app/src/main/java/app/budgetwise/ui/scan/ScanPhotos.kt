package app.budgetwise.ui.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.budgetwise.di.ApplicationScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The photo side of scanning, all off the main thread: opening and shrinking a photo ([PhotoPrep]), and keeping the
 * one being read on disk, so it is still there if the phone closes BudgetWise while the user is in another app.
 */
interface ScanPhotos {
    /** A photo chosen from the phone. */
    suspend fun open(uri: Uri): OpenedPhoto

    /** See [PhotoPrep.tooDark]. */
    suspend fun tooDark(bitmap: Bitmap): Boolean

    /** See [PhotoPrep.prepare]; throws when the photo cannot be encoded or memory runs out. */
    suspend fun prepare(bitmap: Bitmap): PreparedPhoto

    /** Keeps [bytes] as the photo of the scan in progress, in place of any before it. Throws IOException. */
    suspend fun keep(bytes: ByteArray)

    /** The photo [keep] kept, or null when there is none or it cannot be read. */
    suspend fun kept(): PreparedPhoto?

    /** Deletes the kept photo (a receipt is the user's financial data). Returns at once; the delete runs in order after [keep]. */
    fun drop()
}

/** The kept photo lives in the app's own cache, never in the saved state: at up to 3 MB it is too large for that. */
@Singleton
class DefaultScanPhotos @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) : ScanPhotos {
    /** One at a time, in order: a delete never lands after the next scan's photo was written. */
    private val disk = Dispatchers.IO.limitedParallelism(1)
    private val file get() = File(context.cacheDir, "scan/current.jpg")

    override suspend fun open(uri: Uri) = withContext(Dispatchers.Default) { PhotoPrep.open(context, uri) }

    override suspend fun tooDark(bitmap: Bitmap) = withContext(Dispatchers.Default) { PhotoPrep.tooDark(bitmap) }

    override suspend fun prepare(bitmap: Bitmap) = withContext(Dispatchers.Default) { PhotoPrep.prepare(bitmap) }

    override suspend fun keep(bytes: ByteArray) = withContext(disk) {
        val target = file
        target.parentFile?.mkdirs()
        // Whole or not at all: a half-written photo is never read back.
        val partial = File(target.parentFile, "${target.name}.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) throw IOException("the photo could not be kept")
    }

    override suspend fun kept(): PreparedPhoto? = withContext(disk) {
        try {
            val bytes = file.takeIf { it.isFile }?.readBytes() ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { PreparedPhoto(bytes, it) }
        } catch (_: IOException) {
            null
        }
    }

    override fun drop() {
        scope.launch(disk) { file.delete() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ScanPhotosModule {
    @Binds
    abstract fun scanPhotos(impl: DefaultScanPhotos): ScanPhotos
}
