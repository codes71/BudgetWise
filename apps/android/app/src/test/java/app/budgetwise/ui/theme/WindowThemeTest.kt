package app.budgetwise.ui.theme

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import app.budgetwise.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The window draws before Compose does, so its background must be BudgetWiseTheme's or launch flashes. */
@RunWith(RobolectricTestRunner::class)
class WindowThemeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun windowBackground(): Int {
        val themed = ContextThemeWrapper(context, R.style.Theme_BudgetWise)
        val a = themed.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        try {
            return (a.getDrawable(0) as ColorDrawable).color
        } finally {
            a.recycle()
        }
    }

    @Test
    fun appUsesTheBudgetWiseWindowTheme() {
        assertEquals(R.style.Theme_BudgetWise, context.applicationInfo.theme)
    }

    @Test
    fun lightWindowBackgroundIsTheComposeBackground() {
        assertEquals(LightColorScheme.background.toArgb(), windowBackground())
    }

    @Test
    @Config(qualifiers = "night")
    fun darkWindowBackgroundIsTheComposeBackground() {
        assertEquals(DarkColorScheme.background.toArgb(), windowBackground())
    }
}
