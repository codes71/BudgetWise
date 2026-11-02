package app.budgetwise

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The two languages the app is written in. [tag] is the BCP 47 tag its resources use. */
enum class AppLanguage(val tag: String) {
    BURMESE("my"),
    ENGLISH("en"),
}

/** The language the app draws in now: Burmese when its configuration says so (the user's pick or the phone's). */
@Composable
@ReadOnlyComposable
fun currentAppLanguage(): AppLanguage =
    if (LocalConfiguration.current.locales[0].language == AppLanguage.BURMESE.tag) AppLanguage.BURMESE else AppLanguage.ENGLISH

/**
 * The app's own language, picked on the first screen (and later in Settings), apart from the phone's. Until the user
 * picks, the app follows the phone.
 */
interface LanguageStore {
    /** True once the user has passed the language screen; until then a signed-out start opens on it. */
    val chosen: Boolean

    /**
     * Draws the app in [language] from now on. From Android 13 the system keeps it and recreates the activity; below
     * that it is kept here and the caller recreates the activity ([needsRecreate]).
     */
    fun apply(language: AppLanguage)

    fun markChosen()

    /** Whether [apply] leaves recreating the activity to the caller. */
    val needsRecreate: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}

@Singleton
class DefaultLanguageStore @Inject constructor(@ApplicationContext private val context: Context) : LanguageStore {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val chosen: Boolean get() = prefs.getBoolean(KEY_CHOSEN, false)

    override fun apply(language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        } else {
            // edit {} applies, which updates the in-memory copy at once, so the recreated activity reads the new tag.
            prefs.edit { putString(KEY_LANGUAGE, language.tag) }
        }
    }

    override fun markChosen() = prefs.edit { putBoolean(KEY_CHOSEN, true) }

    companion object {
        private const val PREFS = "app_language"
        private const val KEY_CHOSEN = "chosen"
        private const val KEY_LANGUAGE = "language"

        /**
         * For [MainActivity.attachBaseContext] below Android 13, which has no per-app language: [base] with the
         * picked language's configuration, or [base] itself when nothing was picked (the phone's language).
         */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
            val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null) ?: return base
            val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
            return base.createConfigurationContext(config)
        }
    }
}
