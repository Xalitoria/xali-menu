import mc.xalitoria.menu.paper.editor.EditorRelay
import com.typesafe.config.ConfigFactory
import mc.xalitoria.menu.paper.editor.PendingEdits
import mc.xalitoria.menu.paper.editor.RelayCrypto
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parts of sharing the editor through a relay that need no server: the encryption (shared with the web editor) and the held edits. */
class EditorRelayTest {
    private val derived = RelayCrypto.derive("Ab9-_xK2pQ7m")

    @Test
    fun whatIsSealedIsOpenedOnlyWithTheSameKeySessionAndDirection() {
        val sealed = RelayCrypto.seal(derived.key, "s1", "a", "plain".toByteArray())
        assertEquals("plain", RelayCrypto.open(derived.key, "s1", "a", sealed)?.decodeToString())
        assertNull(RelayCrypto.open(derived.key, "s2", "a", sealed))
        assertNull(RelayCrypto.open(derived.key, "s1", "q", sealed))
        assertNull(RelayCrypto.open(RelayCrypto.derive(RelayCrypto.newCode()).key, "s1", "a", sealed))
        val changed = sealed.copyOf().also { it[sealed.size - 1] = (it[sealed.size - 1].toInt() xor 1).toByte() }
        assertNull(RelayCrypto.open(derived.key, "s1", "a", changed))
        assertNull(RelayCrypto.open(derived.key, "s1", "a", ByteArray(5)), "too short to be a message")
    }

    @Test
    fun everyMessageHasItsOwnIvAndEveryCodeIsNew() {
        val a = RelayCrypto.seal(derived.key, "s", "q", ByteArray(1))
        val b = RelayCrypto.seal(derived.key, "s", "q", ByteArray(1))
        assertFalse(a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))
        assertNotEquals(RelayCrypto.newCode(), RelayCrypto.newCode())
    }

    @Test
    fun aShortCodeIsTwelveBase64UrlCharsAndDerivesTheSameSessionAndKeyAsJs() {
        val code = RelayCrypto.newCode()
        assertEquals(RelayCrypto.CODE_LENGTH, code.length)
        assertTrue(RelayCrypto.CODE_PATTERN.matches(code), code)
        assertNotEquals(code, RelayCrypto.newCode())

        // Made by relay.js: deriveCode('Ab9-_xK2pQ7m') + seal(key, session, 'q', {hello: 'short-code'}).
        assertEquals("MDOBu5MeKKxKUvcD8VhhAA", derived.sessionId)
        val fromJs = Base64.getDecoder().decode("kPOxqsUkrsUSn5kElctJU4z5npgHv9A77fJwIoARkB1hN/7mKCi5bFPW7+EVtp5+HsA=")
        assertEquals("""{"hello":"short-code"}""", RelayCrypto.open(derived.key, derived.sessionId, "q", fromJs)?.decodeToString())
    }

    @Test
    fun theShortLinkOmitsTheRelayWhenUsingTheSitesDefaultRelay() {
        assertEquals("https://menu.xali.to/#Ab9-_xK2pQ7m", EditorRelay.shortLink("https://menu.xali.to/", "https://menu.xali.to/relay/", "Ab9-_xK2pQ7m"))
        assertEquals(
            "https://menu.xali.to/#Ab9-_xK2pQ7m&relay=https%3A%2F%2Frelay.example.com",
            EditorRelay.shortLink("https://menu.xali.to", "https://relay.example.com/", "Ab9-_xK2pQ7m"),
        )
        assertEquals("wss://menu.xali.to/relay/s/abc/ws", EditorRelay.wsUri("https://menu.xali.to/relay/", "abc").toString())
        assertEquals("ws://127.0.0.1:8787/relay/s/abc/ws", EditorRelay.wsUri("http://127.0.0.1:8787/relay", "abc").toString())
        assertEquals(true, mc.xalitoria.menu.paper.editor.Wire.read<mc.xalitoria.menu.paper.editor.RelaySession>("""{"id":"a","secret":"b","ws":true}""")?.ws)
        assertEquals(false, mc.xalitoria.menu.paper.editor.Wire.read<mc.xalitoria.menu.paper.editor.RelaySession>("""{"id":"a","secret":"b"}""")?.ws)
    }

    @Test
    fun aNameFromALinkIsPlainText() {
        assertEquals("guest", EditorRelay.clean(null))
        assertEquals("guest", EditorRelay.clean("  "))
        assertEquals("Alex", EditorRelay.clean("  Alex "))
        assertEquals("cRed", EditorRelay.clean("&cRed"))
        assertEquals("ab", EditorRelay.clean("a\nb"))
        assertEquals(32, EditorRelay.clean("x".repeat(100)).length)
    }

    private fun menu() = ConfigFactory.parseString("""{"title": "x"}""").root()

    @Test
    fun heldEditsKeepTheirOrderAndAreTakenOnce() {
        val pending = PendingEdits()
        val a = pending.add("Alex", "shop", menu(), emptyList())
        val b = pending.add("Sam", "kits", menu(), listOf("problem"))
        assertEquals(listOf(a.id, b.id), pending.list().map { it.id })
        assertEquals("Sam", pending.take(b.id)?.from)
        assertNull(pending.take(b.id), "an edit is applied or dropped once")
        assertEquals(1, pending.list().size)
    }

    @Test
    fun aLaterSaveOfTheSameMenuBySameSenderReplacesTheEarlierOne() {
        val pending = PendingEdits()
        pending.add("Alex", "shop", menu(), emptyList())
        pending.add("Sam", "shop", menu(), emptyList())
        val last = pending.add("Alex", "shop", menu(), emptyList())
        assertEquals(listOf("Sam", "Alex"), pending.list().map { it.from })
        assertEquals(last.id, pending.list().last().id)
    }

    @Test
    fun tooManyHeldEditsDropTheOldest() {
        val pending = PendingEdits(limit = 3)
        val ids = (1..5).map { pending.add("p$it", "m", menu(), emptyList()).id }
        assertEquals(ids.takeLast(3), pending.list().map { it.id })
    }
}
