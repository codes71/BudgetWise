package app.budgetwise

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.budgetwise.nav.AppNav
import app.budgetwise.ui.theme.BudgetWiseTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var language: LanguageStore

    // Below Android 13 the picked language is applied here; from 13 the system does it.
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(DefaultLanguageStore.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        holdFirstFrameUntilSessionLoaded()
        setContent {
            BudgetWiseTheme {
                val session by viewModel.session.collectAsStateWithLifecycle()
                session?.let { AppNav(session = it, askLanguage = !language.chosen) }
            }
        }
    }

    /**
     * Keeps the launch window (the system splash screen on API 31+) up until the stored session is known, usually a
     * few tens of milliseconds, so the first frame is already the right screen and never a flash of sign-in.
     */
    private fun holdFirstFrameUntilSessionLoaded() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (viewModel.session.value == null) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }
}
