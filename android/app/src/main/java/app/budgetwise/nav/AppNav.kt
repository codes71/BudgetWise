package app.budgetwise.nav

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.budgetwise.R
import app.budgetwise.data.auth.SessionState
import app.budgetwise.ui.budgets.BudgetsRoute
import app.budgetwise.ui.common.sessionNoticeFor
import app.budgetwise.ui.login.LoginRoute
import app.budgetwise.ui.manual.AddManualRoute
import app.budgetwise.ui.settings.SettingsRoute
import app.budgetwise.ui.transactions.TransactionsRoute
import java.time.Instant

object Routes {
    const val LOGIN = "login"
    const val TRANSACTIONS = "transactions"
    const val MANUAL = "manual"
    const val BUDGETS = "budgets"
    const val SETTINGS = "settings"
}

/** Expired stays in the app (local data visible, a banner asks to sign in); only signed out starts at sign-in. */
fun startDestinationFor(session: SessionState): String = when (session) {
    is SessionState.SignedIn, SessionState.Expired -> Routes.TRANSACTIONS
    SessionState.SignedOut -> Routes.LOGIN
}

private data class TopLevel(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
)

private val TopLevels = listOf(
    TopLevel(Routes.TRANSACTIONS, R.string.nav_transactions, R.drawable.ic_receipt_long, R.drawable.ic_receipt_long_fill1),
    TopLevel(Routes.BUDGETS, R.string.nav_budgets, R.drawable.ic_pie_chart, R.drawable.ic_pie_chart_fill1),
    TopLevel(Routes.SETTINGS, R.string.nav_settings, R.drawable.ic_settings, R.drawable.ic_settings_fill1),
)

private fun NavDestination?.isOn(route: String) = this?.hierarchy?.any { it.route == route } == true

/**
 * The app shell: bottom navigation for the three top-level screens; sign-in and manual add are full screen.
 * Transactions is the root of the signed-in back stack, so the bottom bar pops back to it.
 */
@Composable
fun AppNav(
    session: SessionState,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val start = remember { startDestinationFor(session) }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBottomBar = TopLevels.any { destination.isOn(it.route) }
    val notice = remember(session) { sessionNoticeFor(session, Instant.now()) }

    // Signing out (Settings) ends at sign-in with nothing behind it, whichever screen the user was on.
    LaunchedEffect(session) {
        if (session == SessionState.SignedOut && navController.currentDestination?.route != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    fun openSignIn() = navController.navigate(Routes.LOGIN) { launchSingleTop = true }

    Scaffold(
        modifier = modifier,
        // Each screen handles its own system bar insets; this Scaffold only makes room for the bottom bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevels.forEach { item ->
                        val selected = destination.isOn(item.route)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(Routes.TRANSACTIONS) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(painterResource(if (selected) item.selectedIcon else item.icon), contentDescription = null)
                            },
                            label = { Text(stringResource(item.label), style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(150)) },
        ) {
            composable(Routes.LOGIN) {
                val canGoBack = remember { navController.previousBackStackEntry != null }
                val close = dropUnlessResumed { navController.popBackStack() }
                LoginRoute(
                    onSignedIn = {
                        navController.navigate(Routes.TRANSACTIONS) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onClose = if (canGoBack) close else null,
                )
            }
            composable(Routes.TRANSACTIONS) {
                TransactionsRoute(
                    sessionNotice = notice,
                    onSignIn = ::openSignIn,
                    onAddManual = { navController.navigate(Routes.MANUAL) { launchSingleTop = true } },
                )
            }
            composable(Routes.MANUAL) {
                AddManualRoute(onDone = dropUnlessResumed { navController.popBackStack() })
            }
            composable(Routes.BUDGETS) { BudgetsRoute() }
            composable(Routes.SETTINGS) { SettingsRoute(onSignIn = ::openSignIn) }
        }
    }
}
