import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mc.xalitoria.menu.paper.config.MainConfig.Packs.Handler
import mc.xalitoria.menu.paper.pack.Downloader
import mc.xalitoria.menu.paper.pack.HttpDownloader
import mc.xalitoria.menu.paper.pack.PackInstaller
import mc.xalitoria.menu.paper.pack.PackInstaller.Status

class PackInstall {
    private val dir: Path = createTempDirectory("packs")
    private val plugins = dir.resolve("plugins")
    private val state = dir.resolve("state")

    @AfterTest
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.first))
                zip.write(file.second.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val base = "https://example.test/release"

    /** A release holding [zip] as friends-gui-nexo.zip, with [checksum] beside it. */
    private fun release(zip: ByteArray, checksum: String = PackInstaller.sha256(zip), requests: MutableList<String> = mutableListOf()) = Downloader { url ->
        requests += url
        when (url) {
            "$base/friends-gui-nexo.zip" -> zip
            "$base/friends-gui-nexo.zip.sha256" -> "$checksum  friends-gui-nexo.zip\n".toByteArray()
            else -> error("HTTP 404 for $url")
        }
    }

    private fun install(downloader: Downloader, handler: Handler = Handler.NEXO, packs: List<String> = listOf("friends-gui")) =
        runBlocking { PackInstaller(downloader, plugins, state).install(handler, "$base/", packs) }

    @Test
    fun checksumsAreReadFromSha256sumLines() {
        val hash = "a".repeat(64)
        assertEquals(hash, PackInstaller.parseChecksum("$hash  friends-gui-nexo.zip\n"))
        assertEquals(hash, PackInstaller.parseChecksum(hash.uppercase()))
        assertNull(PackInstaller.parseChecksum("<html>Not Found</html>"))
        assertNull(PackInstaller.parseChecksum("abc"))
        assertNull(PackInstaller.parseChecksum(""))
    }

    @Test
    fun aPackIsUnpackedIntoTheHandlersFolderAndNotFetchedAgainUntilItChanges() {
        val requests = mutableListOf<String>()
        val first = zip("items/friends.yml" to "a", "pack/assets/minecraft/textures/x.png" to "png")
        val result = install(release(first, requests = requests)).single()
        assertEquals(Status.INSTALLED, result.status, result.detail)
        assertEquals("a", plugins.resolve("Nexo/items/friends.yml").readText())
        assertTrue(plugins.resolve("Nexo/pack/assets/minecraft/textures/x.png").exists())
        assertEquals(2, requests.size, "the checksum and the zip")

        requests.clear()
        assertEquals(Status.CURRENT, install(release(first, requests = requests)).single().status)
        assertEquals(listOf("$base/friends-gui-nexo.zip.sha256"), requests, "only the checksum is fetched")

        val second = zip("items/friends.yml" to "b")
        assertEquals(Status.INSTALLED, install(release(second)).single().status)
        assertEquals("b", plugins.resolve("Nexo/items/friends.yml").readText())
    }

    @Test
    fun itemsAdderGetsItsOwnFolderAndFile() {
        val requests = mutableListOf<String>()
        val bytes = zip("contents/friendsystem/configs/items.yml" to "x")
        val downloader = Downloader { url ->
            requests += url
            if (url.endsWith(".sha256")) "${PackInstaller.sha256(bytes)}  f\n".toByteArray() else bytes
        }
        assertEquals(Status.INSTALLED, install(downloader, Handler.ITEMSADDER).single().status)
        assertTrue(plugins.resolve("ItemsAdder/contents/friendsystem/configs/items.yml").exists())
        assertEquals("$base/friends-gui-itemsadder.zip.sha256", requests.first())
    }

    @Test
    fun aZipThatDoesNotMatchItsChecksumWritesNothing() {
        val result = install(release(zip("items/a.yml" to "a"), checksum = "0".repeat(64))).single()
        assertEquals(Status.FAILED, result.status)
        assertTrue("checksum" in result.detail)
        assertFalse(plugins.exists())
        assertFalse(state.exists())
    }

    @Test
    fun aNameThatLeavesTheFolderIsRefusedBeforeAnythingIsWritten() {
        val evil = zip("items/a.yml" to "a", "../../outside.txt" to "x")
        val result = install(release(evil)).single()
        assertEquals(Status.FAILED, result.status)
        assertTrue("outside" in result.detail)
        assertFalse(plugins.resolve("Nexo/items/a.yml").exists(), "not even the files before it")
        assertFalse(dir.resolve("outside.txt").exists())
        assertFailsWith<IllegalArgumentException> { PackInstaller.unzip(evil, plugins.resolve("Nexo")) }
    }

    @Test
    fun aMissingReleaseAndABadNameAreFailuresNotCrashes() {
        val gone = Downloader { url -> error("HTTP 404 for $url") }
        assertEquals(Status.FAILED, install(gone).single().status)
        val results = install(release(zip("a" to "a")), packs = listOf("../etc", "friends-gui"))
        assertEquals(listOf(Status.FAILED, Status.INSTALLED), results.map { it.status }, "one failing doesn't stop the next")
    }

    @Test
    fun noHandlerInstallsNothing() {
        assertEquals(emptyList(), install(Downloader { error("must not be called") }, Handler.NONE))
    }

    @Test
    fun httpDownloaderFollowsRedirectsAndRefusesErrorsAndBigFiles() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun answer(path: String, code: Int, body: ByteArray = ByteArray(0), location: String? = null) = server.createContext(path) { exchange ->
            location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        answer("/asset", 200, "hello".toByteArray())
        answer("/release", 302, location = "http://127.0.0.1:${server.address.port}/asset")
        answer("/big", 200, ByteArray(2048))
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            runBlocking {
                assertEquals("hello", String(HttpDownloader(limit = 1024).get("$url/release")))
                assertTrue("404" in assertFailsWith<IllegalStateException> { HttpDownloader().get("$url/missing") }.message.orEmpty())
                assertTrue("larger" in assertFailsWith<IllegalStateException> { HttpDownloader(limit = 1024).get("$url/big") }.message.orEmpty())
            }
        } finally {
            server.stop(0)
        }
    }
}
