package app.budgetwise

import android.app.NotificationManager
import android.content.Context
import android.widget.Toast
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Per-account reminder affordances only. Shared sync work and downloaded voice models are deliberately kept. */
class AccountDeletionCleanup @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun finish(owner: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelAllWorkByTag("month-end-account:$owner").result.get()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag?.startsWith("month_end/$owner/") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    suspend fun completed() = withContext(Dispatchers.Main.immediate) {
        Toast.makeText(context, DefaultLanguageStore.wrap(context).getString(R.string.account_delete_done), Toast.LENGTH_LONG).show()
    }
}
