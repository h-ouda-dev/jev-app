package app.jev

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.jev.ml.ChecksumException
import app.jev.ml.ModelDownloader
import app.jev.ml.ModelState
import app.jev.ml.SelfTestState
import app.jev.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Runs on a real Android device / emulator in CI:
 * download the published model, prove resume + checksum protection, install,
 * load with ONNX Runtime Android, run the self-test, then exercise the live
 * notification-listener pipeline.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OnDeviceTest {
    private val app: JevApp = ApplicationProvider.getApplicationContext()

    private fun log(msg: String) {
        Log.i(TAG, msg)
        println("$TAG: $msg")
    }

    private fun shell(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
    }

    @Test fun test1_downloadResumeVerifyInstallAndSelfTest() = runBlocking {
        app.models.delete()
        val downloader = ModelDownloader(BuildConfig.MODEL_BASE_URL)
        val manifest = downloader.fetchManifest()
        log("Manifest ${manifest.id} from ${manifest.sourceModel}: ${manifest.files.joinToString { "${it.name} ${it.size}" }}")

        val t0 = System.currentTimeMillis()
        var reported = 0L
        downloader.download(manifest, app.models.stagingDir) { done, _ -> reported = done }
        assertEquals(manifest.totalBytes, reported)
        log("Downloaded ${manifest.totalBytes / 1_000_000} MB in ${(System.currentTimeMillis() - t0) / 1000} s")

        // Resume: keep only the first half of tokenizer.json as a partial download.
        val tok = File(app.models.stagingDir, "tokenizer.json")
        val bytes = tok.readBytes()
        File(app.models.stagingDir, "tokenizer.json.part").writeBytes(bytes.copyOf(bytes.size / 2))
        tok.delete()
        downloader.download(manifest, app.models.stagingDir) { _, _ -> }
        assertTrue("Resumed file must be byte-identical", tok.readBytes().contentEquals(bytes))
        log("Resume from 50% OK")

        // Corruption: a garbage partial file must be caught by the checksum and discarded.
        val cfg = File(app.models.stagingDir, "jev_config.json")
        cfg.delete()
        File(app.models.stagingDir, "jev_config.json.part").writeText("garbage")
        try {
            downloader.download(manifest, app.models.stagingDir) { _, _ -> }
            fail("Corrupted download was not detected")
        } catch (e: ChecksumException) {
            log("Checksum protection OK: ${e.message}")
        }
        assertTrue(!File(app.models.stagingDir, "jev_config.json.part").exists())
        downloader.download(manifest, app.models.stagingDir) { _, _ -> }

        app.models.installFromStaging(manifest)
        app.models.loadIfPresent(true)
        val state = app.models.state.value
        assertTrue("Model failed to load: $state", state is ModelState.Ready)
        log("Model loaded on device: $state")

        val result = app.models.runSelfTest()
        assertTrue("Self-test did not complete: $result", result is SelfTestState.Done)
        val report = (result as SelfTestState.Done).report
        report.results.forEach { r ->
            log("${if (r.passed) "PASS" else "FAIL"} [${r.case.feature}] ${r.case.name} -> ${r.decision?.choice} (${r.millis} ms)")
        }
        log("Self-test ${report.passed}/${report.total}, model alone ${(report.modelOnlyAccuracy * 100).toInt()}%, median ${report.medianMillis} ms")
        assertEquals("Model errors", 0, report.errors)
        assertTrue("Malformed decisions", report.wellFormed)
        assertTrue("Accuracy ${report.accuracy} too low", report.accuracy >= 0.8f)
    }

    @Test fun test2_liveNotificationPipeline() = runBlocking {
        val pkg = app.packageName
        shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        shell("cmd notification allow_listener $pkg/$pkg.service.JevNotificationListener")
        Thread.sleep(3_000) // let the system bind the listener

        val vm = withContext(Dispatchers.Main) { MainViewModel(app) }
        withContext(Dispatchers.Main) { vm.runPipelineTest() }
        val result = withTimeout(60_000) { vm.pipelineTest.first { it != null && !it.running } }!!
        result.checks.forEach { log("${if (it.ok) "OK  " else "FAIL"} ${it.name}: ${it.detail}") }
        assertTrue("Pipeline check failed: ${result.checks.filter { it.required && !it.ok }}", result.allRequiredPassed)
    }

    companion object {
        private const val TAG = "JevTest"
    }
}
