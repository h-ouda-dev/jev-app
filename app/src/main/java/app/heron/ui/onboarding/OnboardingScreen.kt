package app.heron.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.heron.R
import app.heron.core.money.BudgetCategory
import app.heron.core.triage.TriageBucket
import app.heron.ui.MainViewModel
import app.heron.ui.components.IconBadge
import app.heron.ui.components.Monogram
import app.heron.ui.components.Ring
import app.heron.ui.components.Tag
import app.heron.ui.components.icon
import app.heron.ui.theme.Heron
import app.heron.ui.theme.bucketColor
import app.heron.ui.theme.categoryColor
import kotlinx.coroutines.launch

private data class Page(val eyebrow: String, val title: String, val body: String, val art: @Composable () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(vm: MainViewModel, requestAlerts: () -> Unit, onDone: () -> Unit) {
    val pages = listOf(
        Page("Meet Heron", "Calm phone.\nSharp instincts.", "Heron quietly sorts every notification, stops scams before they reach you, and keeps an eye on your money.") { WelcomeArt() },
        Page("Notifications", "Only what matters\ninterrupts you.", "Family and the things that can't wait come through. Promos and noise are muted, and the rest arrives in a calm digest at times you choose.") { SortArt() },
        Page("Scam shield", "Spot the scam\nbefore you tap.", "Every message gets an instant red-flag check: pressure, payment requests, fake banks and couriers, dodgy links. You see exactly why.") { ScamArt() },
        Page("Money", "Know where it goes.\nNo bank login.", "Heron reads the alerts your bank already sends you, sorts spending into categories and catches odd charges and forgotten subscriptions.") { MoneyArt() },
    )
    val pagerState = rememberPagerState { pages.size + 1 }
    val scope = rememberCoroutineScope()
    val isSetup = pagerState.currentPage == pages.size

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HorizontalPager(pagerState, Modifier.fillMaxSize()) { index ->
            if (index < pages.size) StoryPage(pages[index]) else SetupPage(vm, requestAlerts)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Dots(pages.size + 1, pagerState.currentPage)
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { if (isSetup) onDone() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
            ) {
                Text(
                    when {
                        isSetup -> "Start using Heron"
                        pagerState.currentPage == 0 -> "Get started"
                        else -> "Continue"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!isSetup) {
                TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(pages.size) } }) {
                    Text("Skip intro", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun Dots(count: Int, selected: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == selected) 22.dp else 7.dp, label = "dot")
            val c by animateColorAsState(
                if (i == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "dotc",
            )
            Box(Modifier.height(7.dp).width(w).clip(CircleShape).background(c))
        }
    }
}

@Composable
private fun StoryPage(p: Page) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 28.dp).padding(top = 24.dp, bottom = 170.dp),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { p.art() }
        Spacer(Modifier.height(24.dp))
        Text(p.eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(p.title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(14.dp))
        Text(p.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Illustrations: built from real UI pieces so they match the app ----------

@Composable
private fun WelcomeArt() {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(260.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)))
        Box(Modifier.size(190.dp).clip(CircleShape).background(Heron.colors.heroStart), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Heron", modifier = Modifier.size(260.dp))
        }
    }
}

@Composable
private fun MiniNotification(app: String, title: String, text: String, bucket: TriageBucket, modifier: Modifier = Modifier, dim: Boolean = false) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = if (dim) 0.dp else 6.dp,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Monogram(app, bucketColor(bucket), size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Tag(bucket.label, bucketColor(bucket), icon = bucket.icon())
        }
    }
}

@Composable
private fun SortArt() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MiniNotification("Mom", "Mom", "Call me when you land ❤️", TriageBucket.URGENT)
        MiniNotification("Gmail", "Sara · Design", "Mockups for Monday", TriageBucket.CAN_WAIT, Modifier.padding(horizontal = 8.dp))
        MiniNotification("Instagram", "Instagram", "nour.k liked your photo", TriageBucket.DIGEST, Modifier.padding(horizontal = 16.dp), dim = true)
        MiniNotification("SHEIN", "SHEIN", "Flash sale! 70% off", TriageBucket.SILENCE, Modifier.padding(horizontal = 24.dp), dim = true)
    }
}

@Composable
private fun ScamArt() {
    val danger = Heron.colors.danger
    Column(Modifier.fillMaxWidth()) {
        Surface(shape = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(
                "DHL: Your parcel is on hold. Pay the customs fee of QAR 9.50 within 24 hours: dhl-parcel-fee.xyz/pay",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(start = 28.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Warning, danger, size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Likely scam", style = MaterialTheme.typography.titleMedium, color = danger)
                }
                listOf("Asks you to pay", "Creates urgency", "Pretends to be a courier", "Fake website link").forEach {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(danger))
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun MoneyArt() {
    val cats = listOf(BudgetCategory.GROCERIES to 0.32f, BudgetCategory.DINING to 0.24f, BudgetCategory.TRANSPORT to 0.16f, BudgetCategory.SUBSCRIPTIONS to 0.12f)
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 8.dp) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(0.64f, MaterialTheme.colorScheme.primary, size = 92.dp, stroke = 11.dp) {
                    Text("64%", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    Text("Spent this month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("QAR 3,842", style = MaterialTheme.typography.headlineMedium)
                    Text("of 6,000 budget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(18.dp))
            cats.forEach { (c, share) ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(c.icon(), categoryColor(c), size = 30.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${(share * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(10.dp))
            Tag("Spotify went up to 23.99", Heron.colors.caution, icon = Icons.Rounded.Warning)
        }
    }
}

// ---------- Setup ----------

@Composable
private fun SetupPage(vm: MainViewModel, requestAlerts: () -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(vm.hasNotificationAccess()) }
    var alerts by remember { mutableStateOf(vm.canPostNotifications()) }
    var modelRequested by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        access = vm.hasNotificationAccess()
        alerts = vm.canPostNotifications()
        onPauseOrDispose { }
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 190.dp),
    ) {
        IconBadge(Icons.Rounded.Lock, MaterialTheme.colorScheme.primary, size = 56.dp)
        Spacer(Modifier.height(18.dp))
        Text("Private by design", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Heron does all its thinking on this phone. Your notifications, messages and transactions are never uploaded, sold or seen by anyone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        SetupStep(
            n = 1, icon = Icons.Rounded.Notifications, title = "Let Heron see notifications",
            body = "Needed to sort them and check for scams. Android will ask you to confirm.", done = access, required = true,
            action = "Allow",
        ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        SetupStep(
            n = 2, icon = Icons.Rounded.NotificationsActive, title = "Allow Heron's alerts",
            body = "For scam warnings, your digest and odd charges.", done = alerts, required = true, action = "Allow",
        ) { requestAlerts() }
        SetupStep(
            n = 3, icon = Icons.Rounded.AutoAwesome, title = "Make Heron smarter",
            body = "Optional. Downloads Heron's on-device AI (about 300 MB) on Wi-Fi in the background.",
            done = modelRequested, required = false, action = "Download",
        ) { vm.downloadModel(wifiOnly = true); modelRequested = true }
        AnimatedVisibility(!access) {
            Text(
                "You can finish without step 1, but Heron can't sort anything until it's on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SetupStep(n: Int, icon: ImageVector, title: String, body: String, done: Boolean, required: Boolean, action: String, onClick: () -> Unit) {
    val ok = Heron.colors.safe
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (done) IconBadge(Icons.Rounded.CheckCircle, ok, size = 44.dp) else IconBadge(icon, MaterialTheme.colorScheme.primary, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!required) Text("Optional", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.width(10.dp))
            if (done) {
                Text(if (n == 3) "Started" else "Done", style = MaterialTheme.typography.labelLarge, color = ok, fontWeight = FontWeight.Bold)
            } else if (required) {
                Button(onClick = onClick, shape = CircleShape) { Text(action) }
            } else {
                OutlinedButton(onClick = onClick, shape = CircleShape) { Text(action) }
            }
        }
    }
}

