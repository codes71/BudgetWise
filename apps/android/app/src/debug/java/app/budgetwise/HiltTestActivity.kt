package app.budgetwise

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Empty Hilt activity for Robolectric Compose tests of screens that use `hiltViewModel()`
 * (`createAndroidComposeRule<HiltTestActivity>()`). Debug builds only; not exported.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
