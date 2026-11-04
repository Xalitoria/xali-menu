package mc.xalitoria.menu.paper.editor

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Base64
import java.util.zip.GZIPOutputStream

/** The addresses `/xmenu edit` prints. Everything after the `#` stays in the browser; it is never sent to any server. */
internal object EditorLink {
    /** What a link can carry. Chat accepts long links, but not every client or chat plugin does. */
    private const val LIMIT = 30_000

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8)

    /** The link that connects the editor to this server with [token]. */
    fun server(site: String, publicUrl: String, token: String, menu: String?): String =
        "${site.trimEnd('/')}/#server=${encode(publicUrl)}&token=$token" + (menu?.let { "&menu=${encode(it)}" } ?: "")

    /** The link that carries the menu [name] as [json] itself, or null if it is too long for one. */
    fun data(site: String, name: String, json: String): String? {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("""{"menu":$json}""".toByteArray()) } }.toByteArray()
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
        return "${site.trimEnd('/')}/#data=$text&name=${encode(name)}".takeIf { it.length <= LIMIT }
    }
}
