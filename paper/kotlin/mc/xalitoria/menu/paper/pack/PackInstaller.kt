package mc.xalitoria.menu.paper.pack

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mc.xalitoria.menu.paper.config.MainConfig.Packs.Handler

/** Where a pack's bytes come from; the plugin's is HTTP, a test's is a map. */
fun interface Downloader {
    suspend fun get(url: String): ByteArray
}

/** Downloads over HTTPS, following the redirect GitHub puts on a release asset, and refuses anything bigger than [limit] bytes. */
class HttpDownloader(private val limit: Int = LIMIT) : Downloader {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()

    override suspend fun get(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $url" }
            val bytes = body.readNBytes(limit + 1)
            check(bytes.size <= limit) { "$url is larger than ${limit / 1024 / 1024} MB" }
            bytes
        }
    }

    companion object {
        const val LIMIT = 100 * 1024 * 1024
    }
}

/**
 * Installs the zipped GUI packs of a release into the folder of the plugin that handles them (Nexo's or ItemsAdder's).
 * For a pack `friends-gui` and the handler `nexo` it fetches `<url>/friends-gui-nexo.zip.sha256` and then, unless that
 * checksum is the one installed last time, `<url>/friends-gui-nexo.zip`, checks it against the checksum, and unpacks it
 * into `plugins/Nexo`. Nothing is bundled in the plugin: the packs are built and attached to a release by the build.
 */
class PackInstaller(private val downloader: Downloader, private val plugins: Path, private val state: Path) {
    enum class Status { INSTALLED, CURRENT, FAILED }

    class Result(val pack: String, val status: Status, val detail: String)

    /** Installs each of [packs] for [handler] from [url]; a failure of one is its [Result] and does not stop the others. */
    suspend fun install(handler: Handler, url: String, packs: List<String>): List<Result> {
        val folder = handler.folder ?: return emptyList()
        return packs.map { pack ->
            try {
                require(NAME.matches(pack)) { "'$pack' is not a pack name" }
                install(url.trimEnd('/'), "$pack-${handler.id}.zip", plugins.resolve(folder))
            } catch (e: Exception) {
                Result(pack, Status.FAILED, e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    private suspend fun install(base: String, file: String, target: Path): Result {
        val expected = parseChecksum(String(downloader.get("$base/$file.sha256"), Charsets.UTF_8))
            ?: error("$file.sha256 doesn't hold a SHA-256 checksum")
        val marker = state.resolve("$file.sha256")
        if (marker.exists() && marker.readText().trim() == expected && target.exists()) return Result(file, Status.CURRENT, expected)

        val zip = downloader.get("$base/$file")
        val actual = sha256(zip)
        check(actual == expected) { "$file doesn't match its checksum (expected $expected, got $actual)" }
        val count = withContext(Dispatchers.IO) {
            val count = unzip(zip, target)
            state.createDirectories()
            marker.writeText(expected + "\n")
            count
        }
        return Result(file, Status.INSTALLED, "$count files into ${target.fileName}")
    }

    companion object {
        private val NAME = Regex("[A-Za-z0-9._-]+")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private const val MAX_ENTRIES = 5000
        private const val MAX_BYTES = 200L * 1024 * 1024

        private class File(val path: Path, val bytes: ByteArray)

        /** The checksum in a `sha256sum` line (`<hash>  <name>`) or on a line of its own; null if there is none. */
        fun parseChecksum(text: String): String? =
            text.lineSequence().firstOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf(SHA256::matches)?.lowercase()

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * Unpacks [zip] into [target], replacing files that are there. A name that would end up outside of [target], and a
         * zip that holds more than it should, are refused before anything is written. @return the number of files written
         */
        fun unzip(zip: ByteArray, target: Path): Int {
            val root = target.toAbsolutePath().normalize()
            // Looked at in full first, so a bad zip changes nothing.
            val files = mutableListOf<File>()
            var total = 0L
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val path = root.resolve(entry.name).normalize()
                    require(path.startsWith(root)) { "'${entry.name}' would be written outside of ${target.fileName}" }
                    require(files.size < MAX_ENTRIES) { "the zip holds more than $MAX_ENTRIES files" }
                    val bytes = input.readNBytes((MAX_BYTES - total + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    total += bytes.size
                    require(total <= MAX_BYTES) { "the zip unpacks to more than ${MAX_BYTES / 1024 / 1024} MB" }
                    files += File(path, bytes)
                }
            }
            for (file in files) {
                file.path.parent.createDirectories()
                Files.write(file.path, file.bytes)
            }
            return files.size
        }
    }
}
