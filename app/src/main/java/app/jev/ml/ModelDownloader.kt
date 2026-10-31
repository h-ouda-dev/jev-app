package app.jev.ml

import app.jev.core.text.MiniJson
import app.jev.core.text.asList
import app.jev.core.text.asMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class RemoteFile(val name: String, val size: Long, val sha256: String)
data class RemoteManifest(val id: String, val sourceModel: String, val files: List<RemoteFile>) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

class ChecksumException(message: String) : IOException(message)

/**
 * Downloads a model release (manifest.json + files) over HTTPS into [targetDir].
 * Resumable (HTTP Range on *.part files), verified (SHA-256 from the manifest),
 * and atomic per file (only renamed into place once verified).
 */
class ModelDownloader(private val baseUrl: String) {

    suspend fun fetchManifest(): RemoteManifest = withContext(Dispatchers.IO) {
        val text = open(url("manifest.json"), 0).use { (conn, _) -> conn.inputStream.bufferedReader().readText() }
        val m = MiniJson.parse(text).asMap() ?: throw IOException("Bad manifest")
        RemoteManifest(
            id = m["id"] as? String ?: "unknown",
            sourceModel = m["source_model"] as? String ?: "unknown",
            files = m["files"].asList().orEmpty().map { f ->
                val fm = f.asMap() ?: throw IOException("Bad manifest entry")
                RemoteFile(fm["name"] as String, (fm["size"] as Number).toLong(), (fm["sha256"] as String).lowercase())
            },
        )
    }

    /**
     * Downloads every file in [manifest] into [targetDir]. Files already present
     * with the right checksum are skipped, so re-running after a failure is cheap.
     * [onProgress] gets (bytesDone, bytesTotal).
     */
    suspend fun download(manifest: RemoteManifest, targetDir: File, onProgress: suspend (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val total = manifest.totalBytes
        var done = 0L
        for (f in manifest.files) {
            val dest = File(targetDir, f.name)
            if (dest.exists() && dest.length() == f.size && sha256(dest) == f.sha256) {
                done += f.size
                onProgress(done, total)
                continue
            }
            val part = File(targetDir, f.name + ".part")
            if (part.length() > f.size) part.delete()
            var attempt = 0
            while (true) {
                try {
                    fetch(f, part) { fileBytes -> onProgress(done + fileBytes, total) }
                    break
                } catch (e: IOException) {
                    if (e is ChecksumException || ++attempt >= 4) throw e
                    kotlinx.coroutines.delay(2_000L * attempt)
                }
            }
            val actual = sha256(part)
            if (actual != f.sha256) {
                part.delete()
                throw ChecksumException("${f.name} is corrupted (checksum mismatch). Please retry.")
            }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("Could not move ${f.name} into place")
            done += f.size
            onProgress(done, total)
        }
    }

    private suspend fun fetch(f: RemoteFile, part: File, onBytes: suspend (Long) -> Unit) {
        val start = if (part.exists()) part.length() else 0L
        if (start == f.size) return
        open(url(f.name), start).use { (conn, resumed) ->
            val append = resumed && start > 0
            var written = if (append) start else 0L
            FileOutputStream(part, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written - lastReport > 512 * 1024) {
                            lastReport = written
                            onBytes(written)
                        }
                    }
                }
            }
            onBytes(written)
            if (written != f.size) throw IOException("Download of ${f.name} incomplete ($written of ${f.size} bytes)")
        }
    }

    private class Opened(val conn: HttpURLConnection, val resumed: Boolean) : AutoCloseable {
        operator fun component1() = conn
        operator fun component2() = resumed
        override fun close() = conn.disconnect()
    }

    /** Opens a GET, following redirects (GitHub releases redirect to a CDN host). */
    private fun open(start: String, rangeFrom: Long): Opened {
        var target = start
        repeat(6) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Jev-Android")
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            when (val code = conn.responseCode) {
                in 300..399 -> {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                    conn.disconnect()
                    target = URL(URL(target), loc).toString()
                }
                HttpURLConnection.HTTP_OK -> return Opened(conn, resumed = false)
                HttpURLConnection.HTTP_PARTIAL -> return Opened(conn, resumed = true)
                else -> {
                    conn.disconnect()
                    throw IOException("HTTP $code for ${URL(start).path.substringAfterLast('/')}")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun url(name: String) = baseUrl.trimEnd('/') + "/" + name

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
