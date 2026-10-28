package app.budgetwise.asr.download

import android.content.Context
import java.io.File

open class StorageCheck(context: Context) {
    private val dir: File = context.noBackupFilesDir

    /** True when free space covers [bytes] x 1.1 plus a 200 MB margin. */
    open fun hasRoomFor(bytes: Long): Boolean =
        dir.usableSpace >= (bytes * 1.1).toLong() + MARGIN_BYTES

    private companion object {
        const val MARGIN_BYTES = 200L * 1024 * 1024
    }
}
