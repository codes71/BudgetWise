package app.budgetwise.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.budgetwise.R

/**
 * The BudgetWise tile (brand/logo-mark.svg). It carries its own contrast, so it sits on any background. Decorative:
 * the app name is always written next to it.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Image(painter = painterResource(R.drawable.brand_mark), contentDescription = null, modifier = modifier.size(size))
}
