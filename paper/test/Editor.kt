import com.typesafe.config.ConfigFactory
import mc.xalitoria.menu.paper.config.MainConfig
import mc.xalitoria.menu.paper.editor.EditorApi
import mc.xalitoria.menu.paper.editor.EditorGuard
import mc.xalitoria.menu.paper.editor.EditorLink
import mc.xalitoria.menu.paper.editor.EditorMenus
import mc.xalitoria.menu.paper.editor.EditorPresence
import mc.xalitoria.menu.paper.editor.EditorPush
import mc.xalitoria.menu.paper.editor.EditorSessions
import mc.xalitoria.menu.paper.editor.GameFiles
import mc.xalitoria.menu.paper.editor.Held
import mc.xalitoria.menu.paper.editor.Http
import mc.xalitoria.menu.paper.editor.ColorsRequest
import mc.xalitoria.menu.paper.editor.ErrorReply
import mc.xalitoria.menu.paper.editor.MenuRequest
import mc.xalitoria.menu.paper.editor.Problems
import mc.xalitoria.menu.paper.editor.RelayCrypto
import mc.xalitoria.menu.paper.editor.RelayReply
import mc.xalitoria.menu.paper.editor.RelayRequest
import mc.xalitoria.menu.paper.editor.Saved
import mc.xalitoria.menu.paper.editor.Wire
import mc.xalitoria.menu.paper.editor.fontImageIds
import org.bukkit.configuration.file.YamlConfiguration
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

/** The parts of the editor endpoint that need no server: links, the tokens behind them, and the JSON. */
class Editor {
    @Test
    fun onlyTheFontZipsOfTheGameCanBeFetched() {
        assertTrue(GameFiles.allowed("font/unifont.zip"))
        assertTrue(GameFiles.allowed("font/unifont_jp.zip"))
        for (bad in listOf("font/../server.jar", "font/a/b.zip", "textures/x.zip", "font/unifont.hex", "unifont.zip", "font/.zip", "http://evil/font/a.zip", "font/A.zip"))
            assertFalse(GameFiles.allowed(bad), bad)
    }

    @Test
    fun theHashOfAFileComesFromTheAssetIndexAndIsCheckedForShape() {
        val index = """{"objects": {"minecraft/font/unifont.zip": {"hash": "56ec5d3f1e8601c544132c02a99840d52d0aa3f3", "size": 1}, "minecraft/font/bad.zip": {"hash": "../x"}}}"""
        assertEquals("56ec5d3f1e8601c544132c02a99840d52d0aa3f3", GameFiles.hashIn(index, "font/unifont.zip"))
        assertNull(GameFiles.hashIn(index, "font/bad.zip"), "a hash that is not 40 hex digits is not used as a file name")
        assertNull(GameFiles.hashIn(index, "font/none.zip"))
        assertEquals("https://resources.download.minecraft.net/56/56ec5d3f1e8601c544132c02a99840d52d0aa3f3", GameFiles.objectAddress("56ec5d3f1e8601c544132c02a99840d52d0aa3f3").toString())
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", GameFiles.sha1("abc".toByteArray()))
    }

    @Test
    fun anItemsAdderFileNamesItsFontImagesByNamespace() {
        val yaml = YamlConfiguration()
        yaml.loadFromString("info:\n  namespace: shop\nfont_images:\n  coin:\n    path: a.png\n  gem:\n    path: b.png\nitems:\n  ruby: {}\n")
        assertEquals(listOf("shop:coin", "shop:gem"), fontImageIds(yaml))
        assertEquals(emptyList(), fontImageIds(YamlConfiguration().also { it.loadFromString("font_images:\n  x: {}\n") }), "no namespace, no id")
        assertEquals(emptyList(), fontImageIds(YamlConfiguration().also { it.loadFromString("info:\n  namespace: a\n") }))
    }

    @Test
    fun aTokenStandsForItsOwnerUntilItRunsOut() {
        var time = 0L
        val sessions = EditorSessions({ 10 }, { time })
        val token = sessions.create("Steve", autoReload = true, initialMenu = "shop")
        assertEquals("Steve", sessions.owner(token))
        assertEquals(false, sessions.session(token)?.shared)
        assertEquals(true, sessions.session(token)?.autoReload)
        assertEquals("shop", sessions.session(token)?.initialMenu)
        val shared = sessions.create("Steve", shared = true, readOnly = true)
        assertEquals(true, sessions.session(shared)?.shared)
        assertEquals(true, sessions.session(shared)?.readOnly)
        assertEquals(1, sessions.clearShared("Steve"))
        assertNull(sessions.session(shared))
        assertEquals("Steve", sessions.owner(token), "clearing shared sessions keeps direct edit sessions")
        val secondEdit = sessions.create("Alex")
        assertEquals(1, sessions.clearEdit("Alex"))
        assertNull(sessions.session(secondEdit))
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
    fun theWireIsTypedAndKeepsKeysThatLookLikePaths() {
        // A menu travels as a tree and comes back as the config object the plugin reads, with a key such as `a.b` still one key.
        val request = Wire.read<MenuRequest>("""{"menu":{"a.b":1,"nested":{"k":"v"}},"extra":true}""")!!
        val config = Wire.config(request.menu!!)
        assertEquals(setOf("a.b", "nested"), config.keys)
        assertEquals(request.menu, Wire.tree(config), "the tree is what came in")
        assertEquals("""{"problems":["x \"y\""]}""", Wire.write(Problems(listOf("x \"y\""))))
        assertEquals("""{"problems":[],"reloaded":false}""", Wire.write(Saved(emptyList(), reloaded = false)))
        assertEquals("""{"problems":["warn"],"reloaded":true}""", Wire.write(Saved(listOf("warn"), reloaded = true)))
        assertEquals("""{"error":"no"}""", Wire.write(ErrorReply("no")))
        val infoJson = Wire.write(
            mc.xalitoria.menu.paper.editor.Info(
                plugin = "XaliMenu", version = "0.6.0", owner = "Steve", shared = false, readOnly = false,
                menus = emptyList(), pack = false, catalogs = emptyMap(), packSize = null, packUrl = null,
                itemProperties = mapOf("essentials:kit-preview" to mapOf("kit" to "tools")),
                actions = listOf("kiteditor:claim"),
                conditions = listOf("kiteditor:authorized"),
                activators = listOf("myext:emote"),
                completers = listOf("kiteditor:kits"),
            ),
        )
        assertTrue(""""itemProperties":{"essentials:kit-preview":{"kit":"tools"}}""" in infoJson, infoJson)
        assertTrue(""""actions":["kiteditor:claim"]""" in infoJson, infoJson)
        assertTrue(""""conditions":["kiteditor:authorized"]""" in infoJson, infoJson)
        assertTrue(""""activators":["myext:emote"]""" in infoJson, infoJson)
        assertTrue(""""completers":["kiteditor:kits"]""" in infoJson, infoJson)
        // A request of the wrong shape is not one, instead of a value that is null somewhere further on.
        assertNull(Wire.read<MenuRequest>("not json"))
        assertNull(Wire.read<ColorsRequest>("""{"potions":"x"}"""))
        assertEquals(listOf("speed" to 1), Wire.read<ColorsRequest>("""{"potions":[{"type":"water","effects":[{"type":"speed","amplifier":1},{"amplifier":2}]}]}""")!!.potions.single().custom())
    }

    @Test
    fun theSharedClientReadsOnlyAsMuchAsIsAllowedAndFollowsARedirectOnlyWhenAsked() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        fun route(path: String, status: Int, body: ByteArray, location: String? = null) = server.createContext(path) { exchange ->
            location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        route("/ok", 200, ByteArray(10) { 1 })
        route("/big", 200, ByteArray(100) { 1 })
        route("/missing", 404, "no".toByteArray())
        route("/moved", 302, ByteArray(0), location = "/ok")
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val wait = java.time.Duration.ofSeconds(5)
            fun get(path: String, max: Int = 50, follow: Boolean = false) = Http.get(java.net.URI("$base$path"), wait, max, follow)
            assertEquals(10, get("/ok")!!.size)
            assertNull(get("/big"), "an answer longer than the limit is not returned")
            assertEquals(100, get("/big", max = 100)!!.size, "and at the limit it is")
            assertNull(get("/missing"))
            assertNull(get("/moved"), "a redirect is not followed unless asked: a host that is allowed must not lead to another")
            assertEquals(10, get("/moved", follow = true)!!.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun menuNamesCannotLeaveTheFolder() {
        for (good in listOf("shop", "Shop_2", "a-b")) assertTrue(EditorMenus.NAME.matches(good), good)
        for (bad in listOf("", "../x", "a/b", "a.b", "a b", "x".repeat(65))) assertFalse(EditorMenus.NAME.matches(bad), bad)
    }

    @Test
    fun onlySkinsOnMojangsServerAreFetched() {
        val hash = "292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680"
        assertEquals("https://textures.minecraft.net/texture/$hash", EditorApi.skinAddress("http://textures.minecraft.net/texture/$hash").toString())
        assertNotNull(EditorApi.skinAddress("https://textures.minecraft.net/texture/$hash"))
        for (bad in listOf(
            "https://evil.example/texture/$hash", "https://textures.minecraft.net.evil.example/texture/$hash", "https://textures.minecraft.net/other/$hash",
            "https://textures.minecraft.net/texture/$hash?x=1", "https://user@textures.minecraft.net/texture/$hash", "https://textures.minecraft.net:8443/texture/$hash",
            "https://textures.minecraft.net/texture/../x", "file:///etc/passwd", "not a url",
        )) assertNull(EditorApi.skinAddress(bad), bad)
    }

    @Test
    fun aRangeIsReadAsHttpWritesItAndCutToWhatCanBeSent() {
        val limit = EditorApi.MAX_PART.toLong()
        assertEquals(0L..99L, EditorApi.byteRange("bytes=0-99", 1000))
        assertEquals(900L..999L, EditorApi.byteRange("bytes=900-", 1000))
        assertEquals(900L..999L, EditorApi.byteRange("bytes=900-5000", 1000), "cut to the file")
        assertEquals(0L..limit - 1, EditorApi.byteRange("bytes=0-", 100 * limit), "cut to a part that can be sent")
        assertEquals(5L..5L, EditorApi.byteRange(" bytes=5-5 ", 10))
        for (bad in listOf("bytes=1000-1001", "bytes=50-10", "bytes=-5", "items=0-1", "bytes=0-1,5-6", "", "bytes=a-b")) assertNull(EditorApi.byteRange(bad, 1000), bad)
    }

    @Test
    fun ipNormalizationAndMatchingHandleMappedIpv6LoopbackAndRejectHostnames() {
        assertEquals("203.0.113.5", EditorGuard.normalizeIp(" 203.0.113.5 "))
        assertEquals("203.0.113.5", EditorGuard.normalizeIp("::ffff:203.0.113.5"))
        assertTrue(EditorGuard.ipsMatch("203.0.113.5", "::ffff:203.0.113.5"))
        assertTrue(EditorGuard.ipsMatch("127.0.0.1", "::1"))
        assertTrue(EditorGuard.ipsMatch("[::1]", "127.0.0.1"))
        assertFalse(EditorGuard.ipsMatch("203.0.113.5", "203.0.113.6"))
        assertNull(EditorGuard.normalizeIp("localhost"))
        assertNull(EditorGuard.normalizeIp("evil.example"))
        assertNull(EditorGuard.normalizeIp(""))
        assertEquals("198.51.100.7", EditorGuard.resolveClientIp("127.0.0.1", "198.51.100.7", "203.0.113.9", trustProxy = true))
        assertEquals("203.0.113.9", EditorGuard.resolveClientIp("::1", null, "203.0.113.9, 10.0.0.1", trustProxy = true))
        assertEquals("127.0.0.1", EditorGuard.resolveClientIp("127.0.0.1", "198.51.100.7", null, trustProxy = false))
        assertEquals("192.0.2.4", EditorGuard.resolveClientIp("192.0.2.4", "198.51.100.7", null, trustProxy = true), "never trust forwarding headers from non-loopback")
    }

    @Test
    fun saveGuardEnforcesIpPoliciesPermissionsAndSharedLinks() {
        // ADMIN policy: matches owner IP, or another online admin's IP (including console sessions), holds unknown IPs.
        assertEquals(EditorGuard.SaveDecision.Allow, EditorGuard.checkSave(MainConfig.VerifyIp.ADMIN, false, false, "1.2.3.4", true, "1.2.3.4", emptyList()))
        assertEquals(EditorGuard.SaveDecision.Allow, EditorGuard.checkSave(MainConfig.VerifyIp.ADMIN, false, false, "1.2.3.4", true, "5.6.7.8", listOf("5.6.7.8")))
        assertEquals(EditorGuard.SaveDecision.Allow, EditorGuard.checkSave(MainConfig.VerifyIp.ADMIN, false, false, null, true, "5.6.7.8", listOf("5.6.7.8")), "console session saved by online admin")
        val consoleHold = EditorGuard.checkSave(MainConfig.VerifyIp.ADMIN, false, false, null, true, "9.9.9.9", listOf("5.6.7.8"))
        assertTrue(consoleHold is EditorGuard.SaveDecision.Hold && consoleHold.code == "ip-mismatch")

        // OWNER policy: requires exact owner IP; other online admins and console sessions hold.
        assertEquals(EditorGuard.SaveDecision.Allow, EditorGuard.checkSave(MainConfig.VerifyIp.OWNER, false, false, "1.2.3.4", true, "::ffff:1.2.3.4", emptyList()))
        assertTrue(EditorGuard.checkSave(MainConfig.VerifyIp.OWNER, false, false, "1.2.3.4", true, "5.6.7.8", listOf("5.6.7.8")) is EditorGuard.SaveDecision.Hold)
        assertTrue(EditorGuard.checkSave(MainConfig.VerifyIp.OWNER, false, false, null, true, "5.6.7.8", listOf("5.6.7.8")) is EditorGuard.SaveDecision.Hold)

        // OFF policy, readOnly, shared, and revoked permissions.
        assertEquals(EditorGuard.SaveDecision.Allow, EditorGuard.checkSave(MainConfig.VerifyIp.OFF, false, false, "1.2.3.4", true, "9.9.9.9", emptyList()))
        assertEquals(EditorGuard.SaveDecision.ReadOnly, EditorGuard.checkSave(MainConfig.VerifyIp.OFF, true, true, "1.2.3.4", true, "1.2.3.4", emptyList()))
        assertEquals(EditorGuard.SaveDecision.PermissionRevoked, EditorGuard.checkSave(MainConfig.VerifyIp.ADMIN, false, false, "1.2.3.4", false, "1.2.3.4", listOf("1.2.3.4")))
        val sharedHold = EditorGuard.checkSave(MainConfig.VerifyIp.OFF, true, false, "1.2.3.4", true, "1.2.3.4", listOf("1.2.3.4"))
        assertTrue(sharedHold is EditorGuard.SaveDecision.Hold && sharedHold.code == "shared")

        // Shared IPs (NAT/LAN/proxy with non-admin or another player on the same IP) must hold, never prove identity.
        val adminShared = EditorGuard.checkSave(
            MainConfig.VerifyIp.ADMIN, false, false, "1.2.3.4", true, "1.2.3.4",
            onlineAdminIps = listOf("1.2.3.4"),
            onlineNonAdminIps = listOf("1.2.3.4"),
        )
        assertTrue(adminShared is EditorGuard.SaveDecision.Hold && adminShared.code == "shared-ip")
        val ownerShared = EditorGuard.checkSave(
            MainConfig.VerifyIp.OWNER, false, false, "1.2.3.4", true, "1.2.3.4",
            onlineAdminIps = listOf("1.2.3.4"),
            ipSharedWithOtherPlayer = true,
        )
        assertTrue(ownerShared is EditorGuard.SaveDecision.Hold && ownerShared.code == "shared-ip")
    }

    @Test
    fun privilegedActionScannerFindsNestedDangerousActionsWithoutFlaggingConditions() {
        val menu = ConfigFactory.parseString(
            """
            {
              "title": "Shop",
              "open": {
                "require": [{
                  "condition": { "type": "permission", "node": "shop.open" },
                  "deny": [{ "type": "console", "command": "warn {player}" }]
                }]
              },
              "items": {
                "a": {
                  "material": "diamond",
                  "view": {
                    "require": [{
                      "condition": { "type": "and", "conditions": [
                        { "type": "group", "name": "vip" },
                        { "type": "gamemode", "modes": ["SURVIVAL"] }
                      ]}
                    }]
                  },
                  "click": {
                    "left": {
                      "actions": [
                        { "type": "message", "text": "hi" },
                        { "type": "delay", "ticks": 5, "action": { "type": "permission", "node": "admin.*" } },
                        { "type": "group", "name": "owner" },
                        { "type": "meta", "key": "prefix", "value": "&cAdmin" },
                        { "type": "gamemode", "mode": "CREATIVE" }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        ).root()
        val found = EditorGuard.privilegedActions(menu)
        assertEquals(
            listOf(
                "console: warn {player}",
                "permission: +admin.*",
                "group: +owner",
                "meta: prefix=&cAdmin",
                "gamemode: CREATIVE",
            ),
            found,
        )

        // Item literally named `condition` with privileged action in `pass`, plus `player` and command-like `chat`.
        val tricky = ConfigFactory.parseString(
            """
            {
              "items": {
                "condition": {
                  "material": "stone",
                  "slot": 0,
                  "click": {
                    "left": {
                      "require": {
                        "pass": [{ "type": "console", "command": "op evil" }]
                      },
                      "actions": [
                        { "type": "player", "command": "op evil" },
                        { "type": "chat", "text": "/op evil" },
                        { "type": "chat", "text": "hello world" }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        ).root()
        val trickyScan = EditorGuard.scanPrivilegedActions(tricky)
        assertTrue(trickyScan.complete)
        assertEquals(listOf("console: op evil", "player: op evil", "chat: /op evil"), trickyScan.actions)

        // Truncated count and exceeded depth are flagged rather than treated as complete/clean.
        val truncatedScan = EditorGuard.scanPrivilegedActions(menu, limit = 2)
        assertEquals(2, truncatedScan.actions.size)
        assertEquals(5, truncatedScan.totalFound)
        assertTrue(truncatedScan.truncated)
        assertTrue(truncatedScan.scannedAll)
        assertFalse(truncatedScan.complete)

        var deep = """{ "type": "console", "command": "op deep" }"""
        repeat(8) { deep = """{ "type": "all", "actions": [$deep] }""" }
        val deepScan = EditorGuard.scanPrivilegedActions(ConfigFactory.parseString("""{ "on-open": [$deep] }""").root(), maxDepth = 4)
        assertTrue(deepScan.depthExceeded)
        assertFalse(deepScan.scannedAll)
        assertFalse(deepScan.complete)

        // Extension/addon actions are flagged, while extension conditions, catalogs, activators and item properties are not.
        val addonMenu = ConfigFactory.parseString(
            """
            {
              "title": "Kits",
              "open": { "require": [{ "condition": { "type": "kiteditor:authorized", "node": "essentials.kit" } }] },
              "open-with": [{ "type": "myext:emote", "name": "wave" }],
              "pages": { "catalog": { "type": "kiteditor:kits", "usable": false }, "slots": [0], "item": { "material": "chest", "kiteditor:kit-info": { "type": "not:action", "kit": "tools" } } },
              "items": {
                "del": {
                  "material": "tnt",
                  "slot": 1,
                  "click": { "any": { "actions": [{ "type": "kiteditor:delete", "kit": "tools" }] } }
                }
              }
            }
            """.trimIndent(),
        ).root()
        assertEquals(listOf("addon: kiteditor:delete"), EditorGuard.privilegedActions(addonMenu))
    }

    @Test
    fun gameFilesAddressCheckAndCheckedRedirectsBlockSsrf() {
        assertTrue(GameFiles.allowedAddress(java.net.URI("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")))
        assertFalse(GameFiles.allowedAddress(java.net.URI("http://piston-meta.mojang.com/x")))
        assertFalse(GameFiles.allowedAddress(java.net.URI("https://piston-meta.mojang.com:8443/x")))
        assertFalse(GameFiles.allowedAddress(java.net.URI("https://user@piston-meta.mojang.com/x")))
        assertFalse(GameFiles.allowedAddress(java.net.URI("https://169.254.169.254/latest/meta-data/")))

        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/good") { ex ->
            val body = "ok".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
            ex.close()
        }
        server.createContext("/redir-ok") { ex ->
            ex.responseHeaders.add("Location", "/good")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/redir-evil") { ex ->
            ex.responseHeaders.add("Location", "http://169.254.169.254/latest/meta-data/")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.start()
        try {
            val port = server.address.port
            val allowLocal: (java.net.URI) -> Boolean = { it.host == "127.0.0.1" && it.port == port }
            assertEquals("ok", Http.getChecked(java.net.URI("http://127.0.0.1:$port/redir-ok"), java.time.Duration.ofSeconds(5), 64, allowed = allowLocal)?.decodeToString())
            assertNull(Http.getChecked(java.net.URI("http://127.0.0.1:$port/redir-evil"), java.time.Duration.ofSeconds(5), 64, allowed = allowLocal), "redirect to disallowed host must be blocked")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun menuFormatBlocksHoconIncludesAndSystemSubstitutions() {
        val includeAttempt = runCatching { Registry.format.decode("""include file("/etc/passwd")""") }
        assertTrue(includeAttempt.isFailure, "HOCON include directives must be refused")
        val envAttempt = runCatching { Registry.format.decode("""title = ${'$'}{java.home}""") }
        assertTrue(envAttempt.isFailure, "system properties must not resolve in menu files")
        val jsonIncludeAttempt = runCatching {
            ConfigFactory.parseString("""include file("/etc/passwd")""", mc.xalitoria.menu.paper.format.MenuFormat.JSON_PARSE)
        }
        assertTrue(jsonIncludeAttempt.isFailure, "strict JSON parse for packed external values must reject HOCON includes")
    }

    @Test
    fun editorPresenceAssignsDistinctColorsSeparatesInGameAndAnonymousAndExpiresStalePeers() {
        var time = 1000L
        val presence = EditorPresence(ttlMs = 5000L, now = { time })
        val first = presence.update(EditorPresence.Beat("tab-1", "Steve", inGame = true, admin = true, menu = "shop", item = "sword", slot = 10, area = "items", tab = "Editor"))
        assertEquals(1, first.size)
        assertTrue(first[0].self && first[0].inGame && first[0].admin && first[0].name == "Steve")

        val second = presence.update(EditorPresence.Beat("tab-2", null, inGame = false, admin = false, menu = "shop", item = "gem", slot = 12, area = "items", tab = "Editor"))
        assertEquals(2, second.size)
        val self = second.first { it.self }
        val other = second.first { !it.self }
        assertEquals("Anonymous #1", self.name)
        assertFalse(self.inGame)
        assertNotEquals(self.color, other.color, "active peers get distinct highlight colors")

        time = 7000L
        val afterExpiry = presence.update(EditorPresence.Beat("tab-2", null, inGame = false, admin = false, menu = "shop", item = "gem", slot = 12, area = "items", tab = "Editor"))
        assertEquals(1, afterExpiry.size)
        presence.remove("tab-2")
        assertEquals(0, presence.activeCount())
    }

    @Test
    fun editorPushParsesTargetsAndPushesMenusOverDirectAndEncryptedRelayEndpoints() {
        assertTrue(EditorPush.parseTarget("Ab9-_xK2pQ7m", "https://menu.xali.to/relay") is EditorPush.Target.Relay)
        assertTrue(EditorPush.parseTarget("https://menu.xali.to/#Ab9-_xK2pQ7m&relay=https%3A%2F%2Frelay.example.com", "https://menu.xali.to/relay") is EditorPush.Target.Relay)
        assertTrue(EditorPush.parseTarget("https://menu.xali.to/#server=http%3A%2F%2F127.0.0.1%3A8123&token=TOK", "https://menu.xali.to/relay") is EditorPush.Target.Direct)
        assertTrue(EditorPush.parseTarget("https://menu.xali.to/#server=https%3A%2F%2Fplay.example.com%3A8443&token=TOK", "https://menu.xali.to/relay") is EditorPush.Target.Direct)
        assertNull(EditorPush.parseTarget("https://menu.xali.to/#server=http%3A%2F%2Fplay.example.com%3A8123&token=TOK", "https://menu.xali.to/relay"), "non-loopback cleartext HTTP direct push must be refused")
        assertNull(EditorPush.parseTarget("not-a-link", "https://menu.xali.to/relay"))
        assertNull(EditorPush.parseTarget("https://menu.xali.to/#server=file%3A%2F%2F%2Fetc%2Fpasswd&token=TOK", "https://menu.xali.to/relay"))
        val refusedCleartext = EditorPush.push(EditorPush.Target.Direct("http://play.example.com:8123", "TOK"), "shop", ConfigFactory.parseString("""{"title":"S"}""").root(), "Steve")
        assertTrue(refusedCleartext is EditorPush.Result.Failed)

        val code = "Ab9-_xK2pQ7m"
        val derived = RelayCrypto.derive(code)
        var receivedDirectPath: String? = null
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/menus/") { ex ->
            assertEquals("Bearer DIRECT_TOK", ex.requestHeaders.getFirst("Authorization"))
            receivedDirectPath = ex.requestURI.path
            val body = Wire.write(Saved(listOf("minor warning"), reloaded = true)).toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
            ex.close()
        }
        server.createContext("/s/${derived.sessionId}/call") { ex ->
            val reqBytes = ex.requestBody.readAllBytes()
            val plain = RelayCrypto.open(derived.key, derived.sessionId, "q", reqBytes)!!
            val relayReq = Wire.read<RelayRequest>(plain.decodeToString())!!
            assertEquals("PUT", relayReq.method)
            assertEquals("/menus/shop", relayReq.path)
            val heldBody = Wire.write(Held(emptyList(), held = true, id = 7, reason = "IP mismatch (1.2.3.4)"))
            val reply = RelayReply(relayReq.rid!!, 200, "application/json", Base64.getEncoder().encodeToString(heldBody.toByteArray()))
            val sealed = RelayCrypto.seal(derived.key, derived.sessionId, "a", Wire.write(reply).toByteArray())
            ex.sendResponseHeaders(200, sealed.size.toLong())
            ex.responseBody.use { it.write(sealed) }
            ex.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val menu = ConfigFactory.parseString("""{"title": "Shop", "size": 27}""").root()
            val directRes = EditorPush.push(EditorPush.Target.Direct(base, "DIRECT_TOK"), "shop", menu, "Steve")
            assertEquals("/v1/menus/shop", receivedDirectPath)
            assertEquals(EditorPush.Result.Saved(reloaded = true, problems = listOf("minor warning")), directRes)

            val relayRes = EditorPush.push(EditorPush.Target.Relay(base, code), "shop", menu, "Steve")
            assertEquals(EditorPush.Result.Held(id = 7, reason = "IP mismatch (1.2.3.4)", problems = emptyList()), relayRes)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun slowBodyRequestsTimeOutWithoutWedgingWorkers() {
        val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val outcome = java.util.concurrent.CompletableFuture<mc.xalitoria.menu.paper.editor.EditorServer.BodyRead>()
        server.createContext("/slow") { ex ->
            try {
                outcome.complete(mc.xalitoria.menu.paper.editor.EditorServer.readBodyWithTimeout(ex, 1024, 150L, timer))
            } finally {
                ex.close()
            }
        }
        server.start()
        try {
            java.net.Socket("127.0.0.1", server.address.port).use { socket ->
                socket.getOutputStream().write("POST /slow HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 500\r\n\r\npartial".toByteArray())
                socket.getOutputStream().flush()
                assertEquals(
                    mc.xalitoria.menu.paper.editor.EditorServer.BodyRead.TimedOut,
                    outcome.get(3, java.util.concurrent.TimeUnit.SECONDS),
                )
            }
        } finally {
            server.stop(0)
            timer.shutdownNow()
        }
    }
}
