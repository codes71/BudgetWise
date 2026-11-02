package app.budgetwise.ui.common

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit

/**
 * Whether Android would still show its dialog for [permission], remembered on the phone across screens and restarts.
 *
 * shouldShowRequestPermissionRationale alone cannot tell. It is false before the first ask, after the dialog was
 * dismissed (back, or a tap outside, which Android does not count as an answer), and after the second "Don't allow"
 * (Android 11 and later; "Don't ask again" before), when only the app's settings can allow it. It is true only after a
 * real first "Don't allow", so that is what is remembered: a refusal with no rationale after one is the blocked one.
 */
class PermissionMemory(context: Context, private val permission: String) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var tappedAt = 0L

    /** Only the app's settings can allow it now: the button opens them instead of asking. */
    val blocked: Boolean get() = prefs.getBoolean("$permission.blocked", false)

    /** The user tapped to be asked; call just before launching the request. */
    fun tapped() {
        tappedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Records the dialog's answer. [rationale] is shouldShowRequestPermissionRationale right after it. A refusal
     * after [tapped] that came back faster than anyone could answer means Android refused by itself: the permission
     * was blocked outside this flow (in Settings, or before a reinstall).
     */
    fun answered(granted: Boolean, rationale: Boolean) {
        val quick = tappedAt > 0 && SystemClock.elapsedRealtime() - tappedAt < QUICK_MS
        tappedAt = 0
        val refusedOnce = prefs.getBoolean("$permission.refused", false)
        when {
            granted -> allowed()
            rationale -> prefs.edit { putBoolean("$permission.refused", true) }
            refusedOnce || quick -> prefs.edit { putBoolean("$permission.blocked", true) }
            // Dismissed: the dialog comes back next time.
            else -> Unit
        }
    }

    /** It is allowed (in the dialog, or in Settings meanwhile): forget the refusals. */
    fun allowed() {
        if (prefs.contains("$permission.refused") || prefs.contains("$permission.blocked")) {
            prefs.edit {
                remove("$permission.refused")
                remove("$permission.blocked")
            }
        }
    }

    private companion object {
        const val PREFS = "permission_answers"

        /** Faster than a person can read the dialog and tap. */
        const val QUICK_MS = 400L
    }
}
