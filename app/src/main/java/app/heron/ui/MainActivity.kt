package app.heron.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.heron.ui.inbox.InboxScreen
import app.heron.ui.money.MoneyScreen
import app.heron.ui.onboarding.OnboardingScreen
import app.heron.ui.settings.SettingsScreen
import app.heron.ui.shield.ShieldScreen
import app.heron.ui.theme.HeronTheme
import app.heron.ui.today.TodayScreen

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Today", Icons.Rounded.WbSunny),
    INBOX("inbox", "Inbox", Icons.Rounded.Inbox),
    SHIELD("shield", "Shield", Icons.Rounded.Shield),
    MONEY("money", "Money", Icons.Rounded.AccountBalanceWallet),
}

/** Top-level destinations: the four tabs, plus Settings on top of them. */
sealed interface Screen {
    data class Main(val tab: Tab) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val screen = mutableStateOf<Screen>(Screen.Main(Tab.TODAY))
    private val pendingCheck = mutableStateOf<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            HeronTheme {
                val settings by vm.settings.collectAsState()
                if (!settings.onboarded) {
                    OnboardingScreen(
                        vm = vm,
                        requestAlerts = {
                            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onDone = vm::completeOnboarding,
                    )
                } else {
                    AppShell()
                }
            }
        }
    }

    @Composable
    private fun AppShell() {
        val snackbar = remember { SnackbarHostState() }
        val message by vm.message.collectAsState()
        LaunchedEffect(message) {
            message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
        }
        val current = screen.value
        BackHandler(enabled = current != Screen.Main(Tab.TODAY)) {
            screen.value = Screen.Main(Tab.TODAY)
        }
        AnimatedContent(
            targetState = current is Screen.Settings,
            transitionSpec = {
                if (targetState) slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(320)) togetherWith fadeOut(tween(200))
                else fadeIn(tween(250)) togetherWith slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(250))
            },
            label = "settings",
        ) { inSettings ->
            if (inSettings) {
                SettingsScreen(vm, onBack = { screen.value = Screen.Main(Tab.TODAY) }, snackbar = snackbar)
            } else {
                val tab = (screen.value as? Screen.Main)?.tab ?: Tab.TODAY
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = { BottomBar(tab) { screen.value = Screen.Main(it) } },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                            label = "tab",
                        ) { t ->
                            when (t) {
                                Tab.TODAY -> TodayScreen(
                                    vm,
                                    openTab = { screen.value = Screen.Main(it) },
                                    openSettings = { screen.value = Screen.Settings },
                                )
                                Tab.INBOX -> InboxScreen(vm) { text ->
                                    pendingCheck.value = text
                                    vm.check(text)
                                    screen.value = Screen.Main(Tab.SHIELD)
                                }
                                Tab.SHIELD -> ShieldScreen(vm, pendingCheck.value) { pendingCheck.value = null }
                                Tab.MONEY -> MoneyScreen(vm)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = selected == t,
                    onClick = { onSelect(t) },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val shared = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> intent.getStringExtra(EXTRA_CHECK_TEXT)
        }
        if (!shared.isNullOrBlank()) {
            pendingCheck.value = shared
            screen.value = Screen.Main(Tab.SHIELD)
            vm.check(shared)
            return
        }
        when (val route = intent.getStringExtra(EXTRA_TAB)) {
            null -> Unit
            "settings" -> screen.value = Screen.Settings
            else -> Tab.entries.firstOrNull { it.route == route }?.let { screen.value = Screen.Main(it) }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_CHECK_TEXT = "check_text"
    }
}
