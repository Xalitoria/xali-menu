import net.xalitoria.menu.model.AttributeModifier
import net.xalitoria.menu.model.AttributeOperation
import net.xalitoria.menu.model.Book
import net.xalitoria.menu.model.DynamicBoolean
import net.xalitoria.menu.model.DynamicInt
import net.xalitoria.menu.model.Equippable
import net.xalitoria.menu.model.Firework
import net.xalitoria.menu.model.FireworkEffect
import net.xalitoria.menu.model.FireworkType
import net.xalitoria.menu.model.Fish
import net.xalitoria.menu.model.Food
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Menu
import net.xalitoria.menu.model.Rarity
import net.xalitoria.menu.model.Tool
import net.xalitoria.menu.model.ToolRule
import net.xalitoria.menu.model.Trim
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemProperties {
    @Test
    fun theNewPropertiesAreRead() {
        val menu = Registry.format.decode(
            """
            title = "T"
            items {
              a {
                material = golden_apple
                skull = "audizian"
                slot = 0
                damage = 5
                item-name = "&6Apple"
                rarity = EPIC
                glow = true
                max-stack-size = 16
                hide-tooltip = true
                item-model = "mypack:crown"
                stored-enchants { sharpness = 3 }
                potion-type = strong_healing
                food { nutrition = 4, saturation = 2.5, always-edible = true }
                trim { pattern = coast, material = gold }
                book { pages = ["one", "two"], title = T, author = A }
                firework { power = 2, effects = [ { type = STAR, colors = ["#ff0000", "#00ff00"], fade = ["#0000ff"], flicker = true } ] }
                fish { pattern = KOB, body-color = RED }
                attributes = [
                  { attribute = attack_damage, amount = 4.5 }
                  { attribute = max_health, amount = 0.5, operation = MULTIPLY_BASE, slot = chest }
                ]
                tooltip-style = "mypack:fancy"
                equippable { slot = HEAD, model = "mypack:crown", swappable = false }
                tool { mining-speed = 2.5, damage-per-block = 2, rules = [ { blocks = ["#minecraft:mineable/pickaxe", stone], speed = 8, drops = true } ] }
              }
            }
            """.trimIndent()
        )
        val item = menu.items.getValue("a")
        assertEquals("audizian", item.skull)
        assertEquals(DynamicInt(5), item.damage)
        assertEquals("&6Apple", item.itemName)
        assertEquals(Rarity.EPIC, item.rarity)
        assertEquals(DynamicBoolean(true), item.glow)
        assertEquals(DynamicInt(16), item.maxStackSize)
        assertEquals(DynamicBoolean(true), item.hideTooltip)
        assertEquals("mypack:crown", item.itemModel)
        assertEquals(mapOf("sharpness" to 3), item.storedEnchants)
        assertEquals("strong_healing", item.potionType)
        assertEquals(Food(4, 2.5f, true), item.food)
        assertEquals(Trim("coast", "gold"), item.trim)
        assertEquals(Book(["one", "two"], "T", "A"), item.book)
        assertEquals(Firework([FireworkEffect(FireworkType.STAR, ["#ff0000", "#00ff00"], ["#0000ff"], flicker = true)], power = 2), item.firework)
        assertEquals(Fish("KOB", "RED"), item.fish)
        assertEquals(
            [AttributeModifier("attack_damage", 4.5), AttributeModifier("max_health", 0.5, AttributeOperation.MULTIPLY_BASE, "chest")],
            item.attributes,
        )
        assertEquals("mypack:fancy", item.tooltipStyle)
        assertEquals(Equippable("HEAD", "mypack:crown", swappable = false), item.equippable)
        assertEquals(Tool(2.5f, 2, rules = [ToolRule(["#minecraft:mineable/pickaxe", "stone"], 8f, true)]), item.tool)
        assertNull(Item("stone").glow, "a property that is left out stays out")
    }

    @Test
    fun theNewPropertiesAreWrittenAndReadBack() {
        val item = Item(
            "golden_apple", slot = 0, damage = DynamicInt(5), itemName = "x", rarity = Rarity.RARE, glow = DynamicBoolean(false), maxStackSize = DynamicInt(3),
            food = Food(2), trim = Trim("coast", "gold"), book = Book(["p"]),
            firework = Firework([FireworkEffect(colors = ["#ffffff"])]), fish = Fish("KOB"),
            attributes = [AttributeModifier("attack_damage", 2.0, AttributeOperation.MULTIPLY_TOTAL, "hand")], tooltipStyle = "a:b",
            equippable = Equippable("CHEST", cameraOverlay = "a:b", damageOnHurt = false),
            tool = Tool(rules = [ToolRule(["stone"])]),
        )
        val menu = Menu(title = "t", items = mapOf("a" to item))
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, [])))
    }

    @Test
    fun aNumberOrASwitchMayBePlaceholderText() {
        val menu = Registry.format.decode(
            """
            title = t
            items {
              a {
                material = stone, slot = 0
                amount = "%player_level%"
                damage = 7
                model = "%rank_model%"
                glow = "%player_is_op%"
                unbreakable = true
              }
            }
            """.trimIndent()
        )
        val item = menu.items.getValue("a")
        assertEquals(DynamicInt("%player_level%"), item.amount)
        assertNull(item.amount.fixed, "text with a placeholder is only worked out when the item is drawn")
        assertEquals(7, item.damage?.fixed)
        assertEquals(DynamicBoolean("%player_is_op%"), item.glow)
        assertEquals(true, item.unbreakable.fixed)
        assertEquals(menu, Registry.format.decode(Registry.format.encode(menu, [])), "what was written as text stays text, a number stays a number")
        val written = Registry.format.encode(Menu(title = "t", items = mapOf("a" to Item("stone", amount = DynamicInt(3)))), [])
        assertEquals(true, Regex("""amount\s*=\s*3\b""").containsMatchIn(written), "a fixed number is written as a number: $written")
    }

    @Test
    fun placeholderTextWorksOutToANumberOrASwitch() {
        val fill = { text: String -> text.replace("%level%", "12.0").replace("%op%", "Yes") }
        assertEquals(12, DynamicInt("%level%").resolve(fill))
        assertEquals(5, DynamicInt(5).resolve(fill))
        assertNull(DynamicInt("%name%").resolve(fill), "text that is not a number gives nothing, and the default is used")
        assertEquals(true, DynamicBoolean("%op%").resolve(fill))
        assertEquals(false, DynamicBoolean("off").resolve(fill))
        assertNull(DynamicBoolean("%level%").resolve(fill))
    }

    @Test
    fun headsCachesMissingOfflineSkinsWithoutThrowingNpe() {
        var lookups = 0
        var redrawn = 0
        var clock = 1_000L
        val scheduler = java.lang.reflect.Proxy.newProxyInstance(
            io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java.classLoader,
            arrayOf(io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java),
        ) { _, method, args ->
            if (method.name == "runNow") {
                @Suppress("UNCHECKED_CAST")
                (args[1] as java.util.function.Consumer<Any?>).accept(null)
            }
            null
        } as io.papermc.paper.threadedregions.scheduler.AsyncScheduler
        val emptyProfile = java.lang.reflect.Proxy.newProxyInstance(
            com.destroystokyo.paper.profile.PlayerProfile::class.java.classLoader,
            arrayOf(com.destroystokyo.paper.profile.PlayerProfile::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "completeFromCache" -> false
                "hasTextures" -> false
                else -> null
            }
        } as com.destroystokyo.paper.profile.PlayerProfile
        val server = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.Server::class.java.classLoader,
            arrayOf(org.bukkit.Server::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlayerExact" -> null
                "getAsyncScheduler" -> scheduler
                "createProfile" -> {
                    lookups++
                    if (lookups == 1) emptyProfile else throw RuntimeException("mojang down")
                }
                else -> null
            }
        } as org.bukkit.Server
        val plugin = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.plugin.Plugin::class.java.classLoader,
            arrayOf(org.bukkit.plugin.Plugin::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getServer" -> server
                "getLogger" -> java.util.logging.Logger.getAnonymousLogger()
                else -> null
            }
        } as org.bukkit.plugin.Plugin

        val heads = mc.xalitoria.menu.paper.hook.Heads(plugin, skinsRestorer = false, fetch = { mc.xalitoria.menu.paper.hook.Heads.HttpReply(404, null) }, now = { clock })
        assertNull(heads.skinUrl("NoSuchPlayer"))
        assertNull(heads.skinUrl("NoSuchPlayer"), "cached missing entry does not re-query")
        assertEquals(1, lookups)

        // Trigger the throwing branch via async lookup too (no NPE in ConcurrentHashMap).
        assertNull(heads.named("BrokenLookup") { redrawn++ })
        assertNull(heads.named("BrokenLookup") { redrawn++ })
        assertEquals(2, lookups)
        assertEquals(1, redrawn)

        // Missing entries expire after MISSING_FOR_MS so temporary lookup failures recover automatically.
        clock += mc.xalitoria.menu.paper.hook.Heads.MISSING_FOR_MS
        assertNull(heads.skinUrl("NoSuchPlayer"))
        assertEquals(3, lookups)
    }

    @Test
    fun headsFallsBackToMojangWhenPaperReturnsTexturelessProfileAndNotifiesAllConcurrentWaiters() {
        val queued = mutableListOf<() -> Unit>()
        val properties = mutableListOf<com.destroystokyo.paper.profile.ProfileProperty>()
        val scheduler = java.lang.reflect.Proxy.newProxyInstance(
            io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java.classLoader,
            arrayOf(io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java),
        ) { _, method, args ->
            if (method.name == "runNow") {
                @Suppress("UNCHECKED_CAST")
                val consumer = args[1] as java.util.function.Consumer<Any?>
                queued += { consumer.accept(null) }
            }
            null
        } as io.papermc.paper.threadedregions.scheduler.AsyncScheduler

        fun fakeProfile(hasTex: Boolean, collectProps: Boolean = false) = java.lang.reflect.Proxy.newProxyInstance(
            com.destroystokyo.paper.profile.PlayerProfile::class.java.classLoader,
            arrayOf(com.destroystokyo.paper.profile.PlayerProfile::class.java),
        ) { _, method, args ->
            when (method.name) {
                "completeFromCache" -> true // Paper cache returned true, but without textures!
                "hasTextures" -> hasTex || properties.isNotEmpty()
                "setProperty" -> {
                    if (collectProps) properties += args[0] as com.destroystokyo.paper.profile.ProfileProperty
                    null
                }
                "getProperties" -> properties.toSet()
                else -> null
            }
        } as com.destroystokyo.paper.profile.PlayerProfile

        val server = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.Server::class.java.classLoader,
            arrayOf(org.bukkit.Server::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getPlayerExact", "getPlayer", "getOfflinePlayerIfCached" -> null
                "getAsyncScheduler" -> scheduler
                "createProfile" -> if (args.size == 1) fakeProfile(hasTex = false) else fakeProfile(hasTex = true, collectProps = true)
                else -> null
            }
        } as org.bukkit.Server
        val plugin = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.plugin.Plugin::class.java.classLoader,
            arrayOf(org.bukkit.plugin.Plugin::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getServer" -> server
                "getLogger" -> java.util.logging.Logger.getAnonymousLogger()
                else -> null
            }
        } as org.bukkit.plugin.Plugin

        val texturePayload = mc.xalitoria.menu.paper.hook.Heads.encodeUrl("https://textures.minecraft.net/texture/5d889b727be593e570df5d5f3deb6bdf957b14816758d6a63d06b2ca7d100126")
        val heads = mc.xalitoria.menu.paper.hook.Heads(
            plugin,
            skinsRestorer = true, // SkinsRestorerProvider.get() will fail in unit test, verifying graceful fallback
            fetch = { url ->
                when {
                    url.endsWith("/audizign") -> mc.xalitoria.menu.paper.hook.Heads.HttpReply(200, """{"name":"audizign","id":"39b5a89899e24a9892b9ffcb625a59e7"}""")
                    url.contains("/39b5a89899e24a9892b9ffcb625a59e7") ->
                        mc.xalitoria.menu.paper.hook.Heads.HttpReply(200, """{"id":"39b5a89899e24a9892b9ffcb625a59e7","name":"audizign","properties":[{"signature":"SIG","value":"$texturePayload","name":"textures"}]}""")
                    else -> mc.xalitoria.menu.paper.hook.Heads.HttpReply(404, null)
                }
            },
        )

        var firstRedraw = 0
        var secondRedraw = 0
        assertNull(heads.named("audizign") { firstRedraw++ })
        assertNull(heads.named("audizign") { secondRedraw++ })
        assertEquals(1, queued.size, "only one async lookup runs for concurrent callers")

        queued.single().invoke()
        assertEquals(1, firstRedraw)
        assertEquals(1, secondRedraw, "every concurrent menu slot waiting on the same head is redrawn")
        assertEquals(1, properties.size)
        assertEquals("textures", properties.single().name)
        assertEquals(texturePayload, properties.single().value)
        assertEquals("SIG", properties.single().signature)
        assertEquals(true, heads.named("audizign") {} != null, "subsequent calls hit the cache immediately")
    }

    @Test
    fun headsPreservesRememberedSnapshotsAndIgnoresStaleClearedWorkers() {
        val queued = mutableListOf<() -> Unit>()
        val scheduler = java.lang.reflect.Proxy.newProxyInstance(
            io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java.classLoader,
            arrayOf(io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java),
        ) { _, method, args ->
            if (method.name == "runNow") {
                @Suppress("UNCHECKED_CAST")
                val consumer = args[1] as java.util.function.Consumer<Any?>
                queued += { consumer.accept(null) }
            }
            null
        } as io.papermc.paper.threadedregions.scheduler.AsyncScheduler

        fun profile(id: String, hasTex: Boolean) = java.lang.reflect.Proxy.newProxyInstance(
            com.destroystokyo.paper.profile.PlayerProfile::class.java.classLoader,
            arrayOf(com.destroystokyo.paper.profile.PlayerProfile::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "completeFromCache" -> hasTex
                "hasTextures" -> hasTex
                "getName" -> id
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeProfile($id)"
                else -> null
            }
        } as com.destroystokyo.paper.profile.PlayerProfile

        var lookupResult: com.destroystokyo.paper.profile.PlayerProfile = profile("missing", hasTex = false)
        val server = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.Server::class.java.classLoader,
            arrayOf(org.bukkit.Server::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPlayerExact", "getPlayer", "getOfflinePlayerIfCached" -> null
                "getAsyncScheduler" -> scheduler
                "createProfile" -> lookupResult
                else -> null
            }
        } as org.bukkit.Server
        val plugin = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.plugin.Plugin::class.java.classLoader,
            arrayOf(org.bukkit.plugin.Plugin::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getServer" -> server
                "getLogger" -> java.util.logging.Logger.getAnonymousLogger()
                else -> null
            }
        } as org.bukkit.plugin.Plugin

        val rememberedProfile = profile("live-custom", hasTex = true)
        val livePlayer = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.entity.Player::class.java.classLoader,
            arrayOf(org.bukkit.entity.Player::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> "Alex"
                "getUniqueId" -> java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
                "getPlayerProfile" -> rememberedProfile
                else -> null
            }
        } as org.bukkit.entity.Player

        val heads = mc.xalitoria.menu.paper.hook.Heads(plugin, skinsRestorer = false, fetch = { mc.xalitoria.menu.paper.hook.Heads.HttpReply(404, null) })

        // 1. Offline lookup starts, then Alex joins and remember() caches their custom skin before the lookup finishes.
        var waiterA = 0
        assertNull(heads.named("Alex") { waiterA++ })
        assertEquals(1, queued.size)
        heads.remember(livePlayer)
        assertEquals(1, waiterA, "remember() notifies pending waiters immediately")
        // Stale offline lookup finishes with Missing: must NOT overwrite the remembered profile.
        queued.removeFirst().invoke()
        assertEquals(rememberedProfile, heads.named("Alex") {})
        assertEquals(1, waiterA, "stale worker must not notify waiters a second time")

        // 2. clear() while lookup A is in flight; lookup B starts; stale A finishes first and must not steal B's waiters or overwrite B.
        heads.clear()
        var staleWaiter = 0
        var freshWaiter = 0
        assertNull(heads.named("Sam") { staleWaiter++ })
        assertEquals(1, queued.size)
        val workerA = queued.removeFirst()

        heads.clear()
        assertNull(heads.named("Sam") { freshWaiter++ })
        assertEquals(1, queued.size)
        val workerB = queued.removeFirst()

        // Worker A fails with Missing after clear(): must not touch B's waiter or publish Missing.
        workerA.invoke()
        assertEquals(0, freshWaiter)

        // Worker B succeeds with a textured profile: B's waiter gets notified and profile is cached.
        lookupResult = profile("sam-skin", hasTex = true)
        workerB.invoke()
        assertEquals(1, freshWaiter)
        assertEquals(lookupResult, heads.named("Sam") {})
    }

    @Test
    fun headsFallsBackToCraftheadBacksOffMojangOn429AndCoalescesConcurrentSyncLookups() {
        var clock = 10_000L
        val requestedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val skinUrl = "https://textures.minecraft.net/texture/e5cdc3243b2153ab28a159861be643a4fc1e3c17d291cdd3e57a7f370ad676f3"
        val texturePayload = mc.xalitoria.menu.paper.hook.Heads.encodeUrl(skinUrl)

        fun profileWithProps(): com.destroystokyo.paper.profile.PlayerProfile {
            val props = mutableListOf<com.destroystokyo.paper.profile.ProfileProperty>()
            val textures = java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.profile.PlayerTextures::class.java.classLoader,
                arrayOf(org.bukkit.profile.PlayerTextures::class.java),
            ) { _, method, _ ->
                if (method.name == "getSkin") null else null
            } as org.bukkit.profile.PlayerTextures
            return java.lang.reflect.Proxy.newProxyInstance(
                com.destroystokyo.paper.profile.PlayerProfile::class.java.classLoader,
                arrayOf(com.destroystokyo.paper.profile.PlayerProfile::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "completeFromCache" -> false
                    "hasTextures" -> props.isNotEmpty()
                    "getTextures" -> textures
                    "setProperty" -> {
                        props += args[0] as com.destroystokyo.paper.profile.ProfileProperty
                        null
                    }
                    "getProperties" -> props.toSet()
                    else -> null
                }
            } as com.destroystokyo.paper.profile.PlayerProfile
        }

        val server = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.Server::class.java.classLoader,
            arrayOf(org.bukkit.Server::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "createProfile" -> profileWithProps()
                else -> null
            }
        } as org.bukkit.Server
        val plugin = java.lang.reflect.Proxy.newProxyInstance(
            org.bukkit.plugin.Plugin::class.java.classLoader,
            arrayOf(org.bukkit.plugin.Plugin::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getServer" -> server
                "getLogger" -> java.util.logging.Logger.getAnonymousLogger()
                else -> null
            }
        } as org.bukkit.plugin.Plugin

        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val firstEntered = java.util.concurrent.CountDownLatch(1)
        val heads = mc.xalitoria.menu.paper.hook.Heads(
            plugin,
            skinsRestorer = false,
            fetch = { url ->
                requestedUrls.add(url)
                when {
                    url.startsWith("https://api.mojang.com/users/profiles/minecraft/Notch10") -> {
                        firstEntered.countDown()
                        releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        mc.xalitoria.menu.paper.hook.Heads.HttpReply(200, """{"name":"Notch10","id":"3fc5d9c87aeb4b9da2c7994072ffa70d"}""")
                    }
                    url.startsWith("https://sessionserver.mojang.com/") ->
                        mc.xalitoria.menu.paper.hook.Heads.HttpReply(429, null)
                    url.startsWith("https://crafthead.net/profile/") ->
                        mc.xalitoria.menu.paper.hook.Heads.HttpReply(
                            200,
                            """{"id":"3fc5d9c87aeb4b9da2c7994072ffa70d","name":"Notch10","properties":[{"name":"textures","value":"$texturePayload","signature":"SIG"}]}""",
                        )
                    else -> mc.xalitoria.menu.paper.hook.Heads.HttpReply(404, null)
                }
            },
            now = { clock },
        )

        // Two concurrent skinUrl("Notch10") calls coalesce into one lookup, fall back to Crafthead on Mojang 429.
        val first = java.util.concurrent.CompletableFuture.supplyAsync { heads.skinUrl("Notch10") }
        assertEquals(true, firstEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val second = java.util.concurrent.CompletableFuture.supplyAsync { heads.skinUrl("Notch10") }
        Thread.sleep(50)
        releaseFirst.countDown()
        assertEquals(skinUrl, first.get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(skinUrl, second.get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(
            listOf(
                "https://api.mojang.com/users/profiles/minecraft/Notch10",
                "https://sessionserver.mojang.com/session/minecraft/profile/3fc5d9c87aeb4b9da2c7994072ffa70d?unsigned=false",
                "https://crafthead.net/profile/3fc5d9c87aeb4b9da2c7994072ffa70d",
            ),
            requestedUrls,
            "concurrent callers only hit upstream once and fall back cleanly to Crafthead on 429",
        )

        // During MOJANG_BACKOFF_MS, a second player skips Mojang completely and goes straight to Crafthead.
        requestedUrls.clear()
        assertEquals(skinUrl, heads.skinUrl("OtherPlayer"))
        assertEquals(listOf("https://crafthead.net/profile/OtherPlayer"), requestedUrls)

        // After MOJANG_BACKOFF_MS elapses, Mojang is tried again.
        clock += mc.xalitoria.menu.paper.hook.Heads.MOJANG_BACKOFF_MS
        requestedUrls.clear()
        heads.skinUrl("ThirdPlayer")
        assertEquals(true, requestedUrls.first().startsWith("https://api.mojang.com/"))
    }
}
