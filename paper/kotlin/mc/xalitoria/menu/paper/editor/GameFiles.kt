package mc.xalitoria.menu.paper.editor

import mc.xalitoria.menu.paper.XaliMenu
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The files of the game that are not in the jar of the client and that the page cannot download itself, since Mojang's servers do not let other
 * sites read them: the zip files of the fallback font (Unifont), which the editor needs to draw characters the game's own font does not have.
 * They are looked up in the asset index of the version of this server, downloaded once from Mojang, checked against the hash the index gives,
 * and kept in the plugin's folder. Nothing but a zip of the `font` folder is ever fetched.
 */
internal class GameFiles(private val plugin: XaliMenu, private val version: String = plugin.server.minecraftVersion) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val folder: Path get() = plugin.dataFolder.toPath().resolve("cache/game")
    private val hashes = ConcurrentHashMap<String, String>()
    private val lock = Any()

    /** The file at [name] (`font/unifont.zip`) of the game's resources, downloaded if it is not kept yet, or null if it cannot be had. */
    fun file(name: String): Path? {
        if (!allowed(name)) return null
        synchronized(lock) {
            return try {
                val hash = hashes[name] ?: hashOf(name)?.also { hashes[name] = it } ?: return null
                val kept = folder.resolve(hash)
                if (Files.exists(kept)) return kept
                val bytes = get(objectAddress(hash)) ?: return null
                if (sha1(bytes) != hash) return null
                Files.createDirectories(folder)
                val part = folder.resolve("$hash.part")
                Files.write(part, bytes)
                Files.move(part, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                kept
            } catch (e: Exception) {
                plugin.logger.warning("The game file $name could not be fetched: ${e.message}")
                null
            }
        }
    }

    /** The hash of [name] in the asset index of this version, which Mojang's version manifest leads to. */
    private fun hashOf(name: String): String? {
        val manifest = Wire.read<VersionManifest>(get(URI(MANIFEST))?.decodeToString() ?: return null) ?: return null
        val entry = manifest.versions.firstOrNull { it.id == version } ?: return null
        val description = Wire.read<VersionDescription>(get(URI(entry.url))?.decodeToString() ?: return null) ?: return null
        val index = description.assetIndex?.let { get(URI(it.url)) } ?: return null
        return hashIn(index.decodeToString(), name)
    }

    private fun get(address: URI): ByteArray? {
        if (address.scheme != "https" || address.host !in HOSTS) return null
        val request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(60)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return response.body().takeIf { response.statusCode() == 200 && it.size <= MAX_FILE }
    }

    companion object {
        private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        private val HOSTS = setOf("piston-meta.mojang.com", "launchermeta.mojang.com", "resources.download.minecraft.net")
        private const val MAX_FILE = 16 * 1024 * 1024
        private val NAME = Regex("font/[a-z0-9_.-]+\\.zip")

        /** Whether [name] is a file this may fetch: a zip in the game's `font` folder, nothing else. */
        fun allowed(name: String): Boolean = NAME.matches(name) && ".." !in name

        /** Where Mojang keeps the object with [hash]. */
        fun objectAddress(hash: String): URI = URI("https://resources.download.minecraft.net/${hash.take(2)}/$hash")

        /** The hash of `minecraft/<name>` in the text of an asset index, or null. */
        fun hashIn(index: String, name: String): String? {
            val hash = Wire.read<AssetIndex>(index)?.objects?.get("minecraft/$name")?.hash ?: return null
            return hash.takeIf { Regex("[0-9a-f]{40}").matches(it) }
        }

        fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
