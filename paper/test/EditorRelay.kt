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
    private val key = RelayCrypto.keyOf("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA")

    @Test
    fun aMessageMadeByTheWebEditorIsReadHere() {
        // Made by relay.js: seal(key, 'abc', 'q', {hello: 'world'}) with the key of the bytes 1 to 32.
        val fromJs = Base64.getDecoder().decode("1ktsd6tcAC4aORePzU3lMTOFVkolr3bmlWpYCaYU0cQyyJXX4eBf+fP9pBpN")
        assertEquals("""{"hello":"world"}""", RelayCrypto.open(key, "abc", "q", fromJs)?.decodeToString())
    }

    @Test
    fun whatIsSealedIsOpenedOnlyWithTheSameKeySessionAndDirection() {
        val sealed = RelayCrypto.seal(key, "s1", "a", "plain".toByteArray())
        assertEquals("plain", RelayCrypto.open(key, "s1", "a", sealed)?.decodeToString())
        assertNull(RelayCrypto.open(key, "s2", "a", sealed))
        assertNull(RelayCrypto.open(key, "s1", "q", sealed))
        assertNull(RelayCrypto.open(RelayCrypto.keyOf(RelayCrypto.newKey()), "s1", "a", sealed))
        val changed = sealed.copyOf().also { it[sealed.size - 1] = (it[sealed.size - 1].toInt() xor 1).toByte() }
        assertNull(RelayCrypto.open(key, "s1", "a", changed))
        assertNull(RelayCrypto.open(key, "s1", "a", ByteArray(5)), "too short to be a message")
    }

    @Test
    fun everyMessageHasItsOwnIvAndEveryKeyIsNew() {
        val a = RelayCrypto.seal(key, "s", "q", ByteArray(1))
        val b = RelayCrypto.seal(key, "s", "q", ByteArray(1))
        assertFalse(a.copyOfRange(0, 12).contentEquals(b.copyOfRange(0, 12)))
        assertNotEquals(RelayCrypto.newKey(), RelayCrypto.newKey())
        assertEquals(32, Base64.getUrlDecoder().decode(RelayCrypto.newKey()).size)
    }

    @Test
    fun aShortCodeIsTwelveBase64UrlCharsAndDerivesTheSameSessionAndKeyAsJs() {
        val code = RelayCrypto.newCode()
        assertEquals(RelayCrypto.CODE_LENGTH, code.length)
        assertTrue(RelayCrypto.CODE_PATTERN.matches(code), code)
        assertNotEquals(code, RelayCrypto.newCode())

        // Made by relay.js: deriveCode('Ab9-_xK2pQ7m') + seal(key, session, 'q', {hello: 'short-code'}).
        val derived = RelayCrypto.derive("Ab9-_xK2pQ7m")
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
    }

    @Test
    fun theLinkCarriesTheRelayTheSessionAndTheKeyAfterTheHash() {
        val link = EditorRelay.link("https://menu.xali.to/", "https://relay.example.com", "abc", "KEY", "shop")
        assertEquals("https://menu.xali.to/#relay=https%3A%2F%2Frelay.example.com&session=abc&key=KEY&menu=shop", link)
        assertTrue(EditorRelay.link("https://menu.xali.to", "http://localhost:8787", "a", "k", null).endsWith("&key=k"))
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
