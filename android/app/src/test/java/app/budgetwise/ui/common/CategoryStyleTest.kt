package app.budgetwise.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.budgetwise.data.repo.CategoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class CategoryStyleTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun everyBuiltInCategoryHasItsOwnBadge() {
        val styles = CategoryRepository.BUILT_IN.map { builtInCategoryStyle(it) }
        styles.forEach { assertNotNull(it) }
        assertEquals("each built-in has its own icon", 8, styles.map { it!!.icon }.toSet().size)
        assertEquals("each built-in has its own color", 8, styles.map { it!!.light.container }.toSet().size)
        assertNull("a user's own category gets the neutral badge", builtInCategoryStyle("Pets"))
    }

    @Test
    fun badgeIconsAreReadableOnTheirContainers() {
        // WCAG asks 3:1 for graphics; the badges keep text-level 4.5:1 so they also work at small sizes.
        CategoryRepository.BUILT_IN.forEach { name ->
            val style = builtInCategoryStyle(name)!!
            for ((theme, colors) in listOf("light" to style.light, "dark" to style.dark)) {
                val ratio = contrast(colors.content, colors.container)
                assertTrue("$name $theme icon on badge is %.2f:1".format(ratio), ratio >= 4.5)
            }
        }
    }
}
