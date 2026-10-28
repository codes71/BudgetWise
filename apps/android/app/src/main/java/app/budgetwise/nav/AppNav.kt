package app.budgetwise.nav

import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
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
import app.budgetwise.ui.common.Lucide
import app.budgetwise.ui.common.SyncChip
import app.budgetwise.ui.common.sessionNoticeFor
import app.budgetwise.ui.common.syncChipFor
import app.budgetwise.ui.login.LoginRoute
import app.budgetwise.ui.manual.AddManualRoute
import app.budgetwise.ui.settings.SettingsRoute
import app.budgetwise.ui.theme.BudgetWiseTheme
import app.budgetwise.ui.theme.medium
import app.budgetwise.ui.theme.semibold
import app.budgetwise.ui.transactions.TransactionsRoute
import java.time.Instant

object Routes {
    const val LOGIN = "login"
    const val TRANSACTIONS = "transactions"
    const val MANUAL = "manual"
    const val BUDGETS = "budgets"
    const val SETTINGS = "settings"
}

/** Expired stays in the app (local data visible, the header's chip asks to sign in); only signed out starts at sign-in. */
fun startDestinationFor(session: SessionState): String = when (session) {
    is SessionState.SignedIn, is SessionState.SignedInOffline, SessionState.Expired -> Routes.TRANSACTIONS
    SessionState.SignedOut -> Routes.LOGIN
}

private data class TopLevel(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val TopLevels = listOf(
    TopLevel(Routes.TRANSACTIONS, R.string.nav_transactions, Lucide.Receipt),
    TopLevel(Routes.BUDGETS, R.string.nav_budgets, Lucide.Pie),
)

/**
 * Closes the manual add form. Not `dropUnlessResumed`: the Saved event is one-shot and already consumed, so dropping it
 * while the entry is not resumed (app in the background, a transition running) would leave the form spinning with
 * saving = true. Checking the current destination still makes a second call (double tap on close) a no-op.
 */
internal fun NavController.closeManual() {
    if (currentDestination?.route == Routes.MANUAL) popBackStack()
}

private fun NavDestination?.isOn(route: String) = this?.hierarchy?.any { it.route == route } == true

/**
 * The app shell: a bottom bar with the two tabs and the Add pill between them; Settings opens from the gear in each
 * tab's header as a pushed screen. Sign-in, Settings and manual add are full screen. Transactions is the root of the
 * signed-in back stack, so the bottom bar pops back to it.
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
    val shell: ShellViewModel = hiltViewModel()
    val signals by shell.signals.collectAsStateWithLifecycle()
    val chip = syncChipFor(session, signals.online, signals.status, signals.queued, signals.unsynced)

    // The bottom bar saves each tab's back stack (its ViewModels and scroll position) to restore it later. That state
    // shows one user's data, so it is dropped whenever the user changes. [tabsOwner] is the last signed-in user: it is
    // kept through an expired session, and null after sign-out or when the app started expired (owner unknown).
    var tabsOwner by rememberSaveable { mutableStateOf<String?>(null) }
    fun forgetSavedTabs() = TopLevels.forEach { navController.clearBackStack(it.route) }

    // The NavHost sets the graph while it composes, which can come after this effect first runs (first composition).
    // Until the first back stack entry exists there is no graph to read and nothing saved to clear, so the effect waits
    // and runs again once it does.
    val graphReady = backStack != null
    LaunchedEffect(session, graphReady) {
        if (!graphReady) return@LaunchedEffect
        when (session) {
            // Signing out (Settings) ends at sign-in with nothing behind it, whichever screen the user was on.
            SessionState.SignedOut -> {
                if (navController.currentDestination?.route != Routes.LOGIN) {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                forgetSavedTabs()
                tabsOwner = null
            }
            // Also an account switch straight from an expired session, which never passes through SignedOut.
            is SessionState.SignedIn -> if (session.user.id != tabsOwner) {
                forgetSavedTabs()
                tabsOwner = session.user.id
            }
            is SessionState.SignedInOffline -> if (session.user.id != tabsOwner) {
                forgetSavedTabs()
                tabsOwner = session.user.id
            }
            SessionState.Expired -> Unit
        }
    }

    fun openSignIn() = navController.navigate(Routes.LOGIN) { launchSingleTop = true }
    fun openSettings() = navController.navigate(Routes.SETTINGS) { launchSingleTop = true }

    // Sign in opens sign-in; every other chip opens Settings, which says more about sync.
    fun onChip(chip: SyncChip) = if (chip == SyncChip.SIGN_IN) openSignIn() else openSettings()

    Scaffold(
        modifier = modifier,
        containerColor = BudgetWiseTheme.colors.ground,
        // Each screen handles its own system bar insets; this Scaffold only makes room for the bottom bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                BottomBar(
                    destination = destination,
                    onTab = { route ->
                        navController.navigate(route) {
                            popUpTo(Routes.TRANSACTIONS) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onAdd = { navController.navigate(Routes.MANUAL) { launchSingleTop = true } },
                )
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
                    chip = chip,
                    sessionNotice = notice,
                    onChip = { chip?.let(::onChip) },
                    onSettings = ::openSettings,
                    onSignIn = ::openSignIn,
                    onAddManual = { navController.navigate(Routes.MANUAL) { launchSingleTop = true } },
                )
            }
            composable(Routes.MANUAL) {
                AddManualRoute(onDone = navController::closeManual)
            }
            composable(Routes.BUDGETS) {
                BudgetsRoute(chip = chip, onChip = { chip?.let(::onChip) }, onSettings = ::openSettings)
            }
            composable(Routes.SETTINGS) {
                val back = dropUnlessResumed { navController.popBackStack() }
                SettingsRoute(onSignIn = ::openSignIn, onBack = back)
            }
        }
    }
}

/** Transactions, the sage Add pill, Budgets. No floating button anywhere: Add always lives here. */
@Composable
private fun BottomBar(destination: NavDestination?, onTab: (String) -> Unit, onAdd: () -> Unit) {
    val colors = BudgetWiseTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.navBackground)) {
        HorizontalDivider(thickness = 1.dp, color = colors.navBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 79.dp)
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab(TopLevels[0], destination.isOn(TopLevels[0].route), onTab, Modifier.weight(1f))
            AddPill(onAdd, Modifier.weight(1f).padding(horizontal = 6.dp))
            Tab(TopLevels[1], destination.isOn(TopLevels[1].route), onTab, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tab(item: TopLevel, selected: Boolean, onTab: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    val type = BudgetWiseTheme.type
    Column(
        modifier = modifier.selectable(selected = selected, role = Role.Tab, onClick = { onTab(item.route) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (type.burmese) 2.dp else 4.dp),
    ) {
        Box(
            Modifier
                .size(width = 56.dp, height = 30.dp)
                .clip(CircleShape)
                .background(if (selected) colors.sageContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = if (selected) colors.onSageContainer else colors.muted,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = stringResource(item.label),
            style = if (selected) type.size12.semibold else type.size12.medium,
            color = if (selected) colors.ink else colors.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun AddPill(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BudgetWiseTheme.colors
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(colors.sageFill)
            .clickable(role = Role.Button, onClick = onAdd),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.PlusBold, contentDescription = null, tint = colors.onSageFill, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.action_add), style = BudgetWiseTheme.type.size16.semibold, color = colors.onSageFill, maxLines = 1)
    }
}
