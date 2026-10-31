package app.jev.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.jev.ui.screens.InboxScreen
import app.jev.ui.screens.MoneyScreen
import app.jev.ui.screens.SettingsScreen
import app.jev.ui.screens.ShieldScreen
import app.jev.ui.theme.JevTheme

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    INBOX("inbox", "Inbox", Icons.Filled.Inbox),
    SHIELD("shield", "Shield", Icons.Filled.Security),
    MONEY("money", "Money", Icons.Filled.AccountBalanceWallet),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val tab = mutableStateOf(Tab.INBOX)
    private val pendingCheck = mutableStateOf<String?>(null)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Only on first creation, so a rotation doesn't re-run a shared check.
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            JevTheme {
                val snackbar = remember { SnackbarHostState() }
                val message by vm.message.collectAsState()
                LaunchedEffect(message) {
                    message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
                }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab.value == t,
                                    onClick = { tab.value = t },
                                    icon = { Icon(t.icon, contentDescription = null) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab.value) {
                        Tab.INBOX -> InboxScreen(vm, m)
                        Tab.SHIELD -> ShieldScreen(vm, pendingCheck.value, { pendingCheck.value = null }, m)
                        Tab.MONEY -> MoneyScreen(vm, m)
                        Tab.SETTINGS -> SettingsScreen(vm, m)
                    }
                }
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
            tab.value = Tab.SHIELD
            vm.check(shared)
            return
        }
        intent.getStringExtra(EXTRA_TAB)?.let { route -> Tab.entries.firstOrNull { it.route == route }?.let { tab.value = it } }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_CHECK_TEXT = "check_text"
    }
}
