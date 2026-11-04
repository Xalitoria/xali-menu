import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mc.xalitoria.menu.paper.editor.EditorLink
import mc.xalitoria.menu.paper.editor.EditorMenus
import mc.xalitoria.menu.paper.editor.EditorSessions
import mc.xalitoria.menu.paper.editor.Json

/** The parts of the editor endpoint that need no server: links, the tokens behind them, and the JSON. */
class Editor {
    @Test
    fun aTokenStandsForItsOwnerUntilItRunsOut() {
        var time = 0L
        val sessions = EditorSessions({ 10 }, { time })
        val token = sessions.create("Steve")
        assertEquals("Steve", sessions.owner(token))
        time = 9 * 60_000L
        assertEquals("Steve", sessions.owner(token))
        time = 10 * 60_000L + 1
        assertNull(sessions.owner(token))
        assertNull(sessions.owner(token), "and stays gone")
    }

    @Test
    fun tokensAreDifferentAndAnythingElseIsNobody() {
        val sessions = EditorSessions({ 10 })
        val first = sessions.create("A")
        val second = sessions.create("B")
        assertNotEquals(first, second)
        assertTrue(first.length >= 30, "enough bytes that it cannot be guessed")
        assertNull(sessions.owner(null))
        assertNull(sessions.owner(""))
        assertNull(sessions.owner(first + "x"))
        sessions.clear()
        assertNull(sessions.owner(first))
    }

    @Test
    fun theServerLinkCarriesTheAddressAndTheToken() {
        val link = EditorLink.server("https://menu.xali.to/", "https://play.example.com:8443/editor", "TOKEN", "shop")
        assertEquals("https://menu.xali.to/#server=https%3A%2F%2Fplay.example.com%3A8443%2Feditor&token=TOKEN&menu=shop", link)
        assertEquals("https://menu.xali.to/#server=http%3A%2F%2F127.0.0.1%3A8123&token=T", EditorLink.server("https://menu.xali.to", "http://127.0.0.1:8123", "T", null))
    }

    @Test
    fun theDataLinkHoldsTheMenuAndNothingMoreThanTheFragment() {
        val json = """{"title":"&6Shop","items":{"a":{"material":"stone","slot":0}}}"""
        val link = assertNotNull(EditorLink.data("https://menu.xali.to", "shop", json))
        val fragment = link.substringAfter("#data=").substringBefore("&name=")
        assertEquals("shop", link.substringAfter("&name="))
        val bytes = Base64.getUrlDecoder().decode(fragment)
        assertEquals("""{"menu":$json}""", GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().decodeToString())
        assertFalse(link.substringBefore('#').contains("data="), "it is after the #, so no server sees it")
    }

    @Test
    fun aMenuThatIsTooBigForALinkHasNone() {
        // Random text does not compress, so this stays long whatever gzip does to it.
        val random = java.util.Random(1)
        val big = """{"title":"${(1..40_000).joinToString("") { ('a' + random.nextInt(26)).toString() }}"}"""
        assertNull(EditorLink.data("https://menu.xali.to", "big", big))
    }

    @Test
    fun jsonKeepsKeysThatLookLikePaths() {
        val read = Json.read("""{"a.b":1,"list":[1,2.5,"x",null,true],"nested":{"k":"v"}}""").unwrapped()
        assertEquals(setOf("a.b", "list", "nested"), read.keys)
        assertEquals("""{"a.b":1,"nested":{"k":"v"}}""", Json.write(mapOf("a.b" to 1, "nested" to mapOf("k" to "v"))))
        assertEquals("""{"problems":["x \"y\""]}""", Json.write(mapOf("problems" to listOf("x \"y\""))))
    }

    @Test
    fun menuNamesCannotLeaveTheFolder() {
        for (good in listOf("shop", "Shop_2", "a-b")) assertTrue(EditorMenus.NAME.matches(good), good)
        for (bad in listOf("", "../x", "a/b", "a.b", "a b", "x".repeat(65))) assertFalse(EditorMenus.NAME.matches(bad), bad)
    }
}
