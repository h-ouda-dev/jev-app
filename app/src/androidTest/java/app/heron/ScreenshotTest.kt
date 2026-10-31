package app.heron

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.heron.data.DemoData
import app.heron.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Captures every screen with realistic demo data, in light and dark mode.
 * CI pulls /data/local/tmp/heron-shots and publishes it for design review.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    private val app: HeronApp = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { it.readBytes().decodeToString() }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(1_800)
        shell("screencap -p $DIR/$name.png")
        Log.i("HeronTest", "Captured $name")
    }

    private fun launch(extra: Intent.() -> Unit = {}): ActivityScenario<MainActivity> {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extra)
        return ActivityScenario.launch(intent)
    }

    @Test fun captureAllScreens() {
        val pkg = app.packageName
        shell("mkdir -p $DIR")
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        shell("cmd notification allow_listener $pkg/$pkg.service.HeronNotificationListener")
        runBlocking { DemoData.seed(app) }

        for (mode in listOf("light", "dark")) {
            shell("cmd uimode night ${if (mode == "dark") "yes" else "no"}")
            Thread.sleep(1_000)

            app.settings.update { it.copy(onboarded = false) }
            launch().use {
                capture("${mode}_01_onboarding_welcome")
                repeat(3) { i ->
                    shell("input swipe 900 1100 150 1100 250")
                    capture("${mode}_0${i + 2}_onboarding_${listOf("sort", "scam", "money")[i]}")
                }
                shell("input swipe 900 1100 150 1100 250")
                capture("${mode}_05_onboarding_setup")
            }
            app.settings.update { it.copy(onboarded = true) }

            listOf("today", "inbox", "shield", "money", "settings").forEachIndexed { i, tab ->
                launch { putExtra(MainActivity.EXTRA_TAB, tab) }.use { capture("${mode}_1${i}_$tab") }
            }
            launch {
                putExtra(
                    MainActivity.EXTRA_CHECK_TEXT,
                    "QNB: Your card has been suspended due to unusual activity. Verify within 24 hours at qnb-secure-verify.com",
                )
            }.use {
                shell("input swipe 540 1700 540 700 300")
                capture("${mode}_20_shield_report")
            }
            launch { putExtra(MainActivity.EXTRA_TAB, "money") }.use {
                shell("input swipe 540 1800 540 500 300")
                capture("${mode}_21_money_scrolled")
            }
            launch { putExtra(MainActivity.EXTRA_TAB, "today") }.use {
                shell("input swipe 540 1800 540 500 300")
                capture("${mode}_22_today_scrolled")
            }
        }
        shell("cmd uimode night no")
    }

    companion object {
        const val DIR = "/data/local/tmp/heron-shots"
    }
}
