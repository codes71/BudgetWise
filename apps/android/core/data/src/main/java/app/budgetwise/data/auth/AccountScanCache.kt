package app.budgetwise.data.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Private receipt photos, bound to both the account and one scan. No account IDs appear in file names. */
@Singleton
class AccountScanCache internal constructor(private val directory: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, "scan"))

    internal class Binding(val owner: String)
    private val disk = Mutex()
    private val bindingLock = Any()
    private var active: Binding? = null
    private var current: Lease? = null

    /**
     * Awaited before the new owner is published. At startup the same owner's files survive, so an interrupted scan
     * can be restored. Legacy unowned photos and all other owners' files are removed, including partial writes.
     */
    suspend fun activate(owner: String?) = withContext(Dispatchers.IO) {
        disk.withLock {
            val keep = owner?.let(::ownerKey)
            directory.listFiles()?.filter { !it.isDirectory || it.name != keep }?.forEach(::delete)
            synchronized(bindingLock) {
                if (active?.owner != owner) {
                    active = owner?.let(::Binding)
                    current = null
                }
            }
        }
    }

    /** [restoredId] comes from this scan's SavedStateHandle, never from a URI or an account ID. */
    fun begin(restoredId: String? = null): Lease = synchronized(bindingLock) { beginBound(active, restoredId) }

    /** A transition preparing its disk cleanup must not let the still-visible previous account adopt the next one. */
    fun beginForOwner(owner: String?, restoredId: String? = null): Lease = synchronized(bindingLock) {
        beginBound(active?.takeIf { it.owner == owner }, restoredId)
    }

    private fun beginBound(binding: Binding?, restoredId: String?): Lease {
        val id = restoredId?.takeIf { value -> runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false) }
            ?: UUID.randomUUID().toString()
        return Lease(binding, id).also { if (binding != null) current = it }
    }

    /** Awaited while the deletion receipt is still pending. An inactive owner's recovery keeps the active scan. */
    suspend fun removeOwner(owner: String) = withContext(Dispatchers.IO) {
        disk.withLock {
            synchronized(bindingLock) {
                if (active?.owner == owner) {
                    active = null
                    current = null
                }
            }
            delete(File(directory, ownerKey(owner)))
            // Upgrade cleanup: these predate account ownership and must not outlive a deletion.
            directory.listFiles()?.filter { !it.isDirectory }?.forEach(::delete)
        }
    }

    inner class Lease internal constructor(private val binding: Binding?, val id: String) {
        val owner: String? get() = binding?.owner
        val active: Boolean get() = isCurrent()
        private val folder get() = File(File(directory, ownerKey(checkNotNull(owner))), id)

        private fun isCurrent() = synchronized(bindingLock) {
            binding != null && this@AccountScanCache.active === binding && current === this
        }

        /** Retaking rotates the scan token immediately, without ever adopting a different account's binding. */
        fun next(): Lease = synchronized(bindingLock) {
            Lease(binding, UUID.randomUUID().toString()).also { if (current === this) current = it }
        }

        suspend fun prune() = withContext(Dispatchers.IO) {
            disk.withLock { if (isCurrent()) pruneOtherScans() }
        }

        suspend fun keep(bytes: ByteArray) = withContext(Dispatchers.IO) {
            disk.withLock {
                if (!isCurrent()) throw IOException("the scan account changed")
                pruneOtherScans()
                val target = File(folder, "current.jpg")
                if (!folder.isDirectory && !folder.mkdirs()) throw IOException("the photo could not be kept")
                val partial = File(folder, "current.jpg.part")
                try {
                    partial.writeBytes(bytes)
                    if (!partial.renameTo(target)) throw IOException("the photo could not be kept")
                } finally {
                    delete(partial)
                }
            }
        }

        suspend fun kept(): ByteArray? = withContext(Dispatchers.IO) {
            disk.withLock {
                if (!isCurrent()) return@withLock null
                pruneOtherScans()
                // A killed write never replaces the last complete photo.
                delete(File(folder, "current.jpg.part"))
                File(folder, "current.jpg").takeIf { it.isFile }?.readBytes()
            }
        }

        suspend fun drop() = withContext(Dispatchers.IO) {
            disk.withLock {
                // A restored screen can have the same ID. Its old screen must not remove that recovered photo.
                synchronized(bindingLock) {
                    val reused = current?.let { it !== this@Lease && it.owner == owner && it.id == id } == true
                    if (owner != null && !reused) delete(folder)
                }
            }
        }

        private fun pruneOtherScans() {
            folder.parentFile?.listFiles()?.filter { it.name != id }?.forEach(::delete)
        }
    }

    private fun ownerKey(owner: String) = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun delete(file: File) {
        if (file.exists() && !file.deleteRecursively()) throw IOException("the scan photo could not be removed")
    }
}
