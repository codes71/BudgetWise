package app.budgetwise.ui.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.budgetwise.data.auth.AccountScanCache
import app.budgetwise.data.auth.SessionManager
import app.budgetwise.di.ApplicationScope
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    /** Capture this account and scan once, so a late callback cannot read or erase a different scan. */
    fun begin(restoredId: String? = null): ScanPhotoStore

    /** A photo chosen from the phone. */
    suspend fun open(uri: Uri): OpenedPhoto

    /** See [PhotoPrep.tooDark]. */
    suspend fun tooDark(bitmap: Bitmap): Boolean

    /** See [PhotoPrep.prepare]; throws when the photo cannot be encoded or memory runs out. */
    suspend fun prepare(bitmap: Bitmap): PreparedPhoto
}

interface ScanPhotoStore {
    val owner: String?
    val id: String
    val active: Boolean

    /** Keeps [bytes] as the photo of the scan in progress, in place of any before it. Throws IOException. */
    suspend fun keep(bytes: ByteArray)

    /** The photo [keep] kept, or null when there is none or it cannot be read. */
    suspend fun kept(): PreparedPhoto?

    /** Deletes this scan's complete and partial photos. Account deletion uses the awaited cache cleanup. */
    fun drop()
}

/** The kept photo lives in the app's own cache, never in the saved state: at up to 3 MB it is too large for that. */
@Singleton
class DefaultScanPhotos @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val cache: AccountScanCache,
    private val session: SessionManager,
) : ScanPhotos {
    override suspend fun open(uri: Uri) = withContext(Dispatchers.Default) { PhotoPrep.open(context, uri) }

    override suspend fun tooDark(bitmap: Bitmap) = withContext(Dispatchers.Default) { PhotoPrep.tooDark(bitmap) }

    override suspend fun prepare(bitmap: Bitmap) = withContext(Dispatchers.Default) { PhotoPrep.prepare(bitmap) }

    override fun begin(restoredId: String?): ScanPhotoStore {
        val first = cache.beginForOwner(session.writableUserId, restoredId)
        scope.launch { try { first.prune() } catch (_: IOException) { } }
        return object : ScanPhotoStore {
            @Volatile private var lease = first
            override val owner get() = lease.owner
            override val id get() = lease.id
            override val active get() = lease.active
            override suspend fun keep(bytes: ByteArray) = lease.keep(bytes)

            override suspend fun kept(): PreparedPhoto? {
                val expected = lease
                return withContext(Dispatchers.Default) {
                    try {
                        val bytes = expected.kept() ?: return@withContext null
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { PreparedPhoto(bytes, it) }
                            ?.takeIf { expected.active }
                    } catch (_: IOException) {
                        null
                    }
                }
            }

            override fun drop() {
                val abandoned = lease
                val next = abandoned.next()
                lease = next
                scope.launch {
                    try {
                        abandoned.drop()
                        next.prune()
                    } catch (_: IOException) { /* Deletion's awaited cleanup will retry failures. */ }
                }
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ScanPhotosModule {
    @Binds
    abstract fun scanPhotos(impl: DefaultScanPhotos): ScanPhotos
}
