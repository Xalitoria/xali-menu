import fixture.shared.Seen
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.paper.extension.Extensions
import mc.xalitoria.menu.paper.format.MenuFormat
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.io.File
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.logging.Logger
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Extension jars, read from a folder: the jars are made here out of the classes of `fixture/ext/<name>`, which the plugin's own
 * loader is not allowed to see, so that they are really loaded from the jar by the extension's loader.
 */
class ExtensionLoading {
    /** Hides the fixtures from the loader the extensions get as their parent, as the plugin's loader does not have them. */
    private object Hiding : ClassLoader(Extensions::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("fixture.ext.") || name.startsWith("fixture.dep.")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

    private val classes = File(Seen::class.java.protectionDomain.codeSource.location.toURI())

    private val plugin = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args[0]
            "hashCode" -> 1
            else -> null
        }
    } as Plugin

    private lateinit var folder: Path

    private inner class FakeHost : Extensions.Host {
        val enabled = HashMap<String, ClassLoader>()
        val listening = ArrayList<Listener>()
        val released = ArrayList<AutoCloseable>()
        val fired = ArrayList<String>()
        override val plugin: Plugin get() = this@ExtensionLoading.plugin
        override val folder: Path get() = this@ExtensionLoading.folder
        override val logger: Logger = Logger.getLogger("ExtensionLoading").also { it.useParentHandlers = false }
        override val parent: ClassLoader get() = Hiding
        override fun enabledPlugins(): Map<String, ClassLoader> = enabled
        override fun listen(listener: Listener) { listening += listener }
        override fun unlisten(listener: Listener) { listening -= listener }
        override fun fire(name: String, player: Player, values: Map<String, String>): Boolean { fired += name; return true }
        override fun release(loader: AutoCloseable) { released += loader; loader.close() }
    }

    private val registry = TypeRegistry().also {
        Actions.register(it)
        Conditions.register(it)
    }
    private val host = FakeHost()
    private val extensions = Extensions(host, registry)

    @BeforeTest
    fun setUp() {
        Seen.reset()
        folder = Files.createTempDirectory("extensions")
    }

    @AfterTest
    fun tearDown() {
        extensions.unload()
        folder.toFile().deleteRecursively()
    }

    /** Writes `<name>.jar` into the folder with the classes of the packages under `fixture/ext`, and the services file if one is given. */
    private fun jar(name: String, vararg packages: String, services: List<String>? = null, into: Path = folder) {
        into.createDirectories()
        JarOutputStream(Files.newOutputStream(into.resolve("$name.jar"))).use { out ->
            for (pack in packages) {
                val dir = File(classes, pack)
                for (file in dir.walkTopDown().filter { it.isFile && it.extension == "class" }) {
                    out.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                    out.write(file.readBytes())
                    out.closeEntry()
                }
            }
            if (services != null) {
                out.putNextEntry(JarEntry("META-INF/services/net.xalitoria.menu.MenuExtension"))
                out.write(services.joinToString("\n").toByteArray())
                out.closeEntry()
            }
        }
    }

    private val menu = """
        title = "x"
        items.a {
          material = stone
          slot = 0
          click.any {
            require = { require = [{ condition = { type = "plain:yes" } }] }
            actions = [{ type = "plain:ping", n = 4 }]
          }
        }
    """.trimIndent()

    private fun decodes(): Boolean = runCatching { MenuFormat(registry.module, registry.externalNames, registry.activatorNames).decode(menu) }.isSuccess

    @Test
    fun theFolderIsMadeWhenItIsNotThere() {
        folder.toFile().deleteRecursively()
        assertEquals(0, extensions.load())
        assertTrue(folder.exists())
        assertEquals(emptyList(), extensions.entries)
    }

    @Test
    fun aJarIsReadAndWhatItAddsIsNamedAfterIt() {
        jar("plain", "fixture/ext/plain")
        assertFalse(decodes(), "the menu needs the extension")
        assertEquals(1, extensions.load())
        val entry = assertNotNull(extensions.find("plain"))
        assertEquals(Extensions.State.Registered, entry.state)
        assertEquals("1.2.3", entry.version)
        assertEquals("tests", entry.author)
        assertEquals(setOf("plain:ping", "plain:yes", "plain:rows"), registry.externalNames)
        assertEquals(setOf("plain:emote"), registry.activatorNames)
        assertEquals(setOf("plainsource"), registry.sources.keys)
        assertEquals(mapOf("name" to "x"), registry.catalogInfo["plain:rows"])
        assertEquals(mapOf("world" to "w"), registry.catalogSettings["plain:rows"])
        assertTrue(decodes(), "the menu is read with what the extension added")
        assertEquals(
            listOf("action plain:ping", "condition plain:yes", "catalog plain:rows", "activator plain:emote", "item source plainsource:"),
            extensions.added(entry),
        )
        assertEquals(1, host.listening.size)
        assertTrue(Seen.log.single().startsWith("plain registered with data in plain"))
    }

    @Test
    fun theClassesComeFromTheJarAndTheAPIFromThePlugin() {
        jar("plain", "fixture/ext/plain")
        extensions.load()
        val extension = assertNotNull(extensions.find("plain")?.extension)
        assertTrue(extension.javaClass.classLoader is URLClassLoader, "loaded from the jar")
        assertEquals(net.xalitoria.menu.MenuExtension::class.java.name, extension.javaClass.superclass.name)
        assertTrue(net.xalitoria.menu.MenuExtension::class.java === extension.javaClass.superclass, "the API is the plugin's, not a copy")
    }

    @Test
    fun anActivatorOfAnExtensionFiresUnderItsFullName() {
        jar("plain", "fixture/ext/plain")
        extensions.load()
        Seen.activator!!.fire(proxyPlayer(), emptyMap())
        assertEquals(listOf("plain:emote"), host.fired)
    }

    private fun proxyPlayer(): Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, _, _ -> null } as Player

    @Test
    fun unloadingTakesEverythingAwayAndLetsGoOfTheJar() {
        jar("plain", "fixture/ext/plain")
        extensions.load()
        extensions.unload()
        assertEquals(emptySet(), registry.externalNames)
        assertEquals(emptySet(), registry.activatorNames)
        assertEquals(emptySet(), registry.sources.keys)
        assertEquals(emptySet(), registry.catalogInfo.keys.filter { it.startsWith("plain:") }.toSet())
        assertEquals(emptyList(), host.listening)
        assertEquals(1, host.released.size)
        assertEquals(emptyList(), extensions.entries)
        assertTrue("plain unregistered" in Seen.log)
        assertFalse(decodes())
        // And it can be read again.
        assertEquals(1, extensions.load())
        assertTrue(decodes())
    }

    @Test
    fun loadingTwiceWithoutUnloadingIsAMistake() {
        jar("plain", "fixture/ext/plain")
        extensions.load()
        assertFailsWith<IllegalStateException> { extensions.load() }
    }

    @Test
    fun anExtensionWaitsForItsPluginsAndGoesWithThem() {
        jar("needy", "fixture/ext/needy")
        assertEquals(0, extensions.load())
        val entry = assertNotNull(extensions.find("needy"))
        assertEquals(Extensions.State.Waiting(listOf("Needed")), entry.state)
        assertEquals(emptySet(), registry.externalNames)

        // Another plugin comes; the extension is not for it.
        host.enabled["other"] = Hiding
        assertFalse(extensions.pluginEnabled("Other"))

        // The plugin it is for has classes that only its own loader has.
        val dependency = dependencyJar()
        val loader = URLClassLoader(arrayOf(dependency.toUri().toURL()), Hiding)
        host.enabled["needed"] = loader
        assertTrue(extensions.pluginEnabled("Needed"), "the name is not case sensitive")
        assertEquals(Extensions.State.Registered, entry.state)
        assertEquals(setOf("needy:go"), registry.externalNames)
        assertSame(loader, Seen.depLoader, "the class of the plugin was found through the plugin's loader")

        assertTrue(extensions.pluginDisabled("needed"))
        assertEquals(Extensions.State.Waiting(listOf("needed")), entry.state)
        assertEquals(emptySet(), registry.externalNames)
        assertTrue("needy unregistered" in Seen.log)
        assertFalse(extensions.pluginDisabled("needed"), "nothing left to take away")

        assertTrue(extensions.pluginEnabled("Needed"))
        assertEquals(setOf("needy:go"), registry.externalNames)
        loader.close()
    }

    private fun dependencyJar(): Path {
        val dir = Files.createTempDirectory("dependency").also { it.toFile().deleteOnExit() }
        val file = dir.resolve("dependency.jar")
        JarOutputStream(Files.newOutputStream(file)).use { out ->
            out.putNextEntry(JarEntry("fixture/dep/Dep.class"))
            out.write(File(classes, "fixture/dep/Dep.class").readBytes())
            out.closeEntry()
        }
        return file
    }

    @Test
    fun aRegistrationThatFailsIsTakenBack() {
        jar("broken", "fixture/ext/broken")
        assertEquals(0, extensions.load())
        assertEquals(Extensions.State.Failed("boom"), extensions.find("broken")?.state)
        assertEquals(emptySet(), registry.externalNames, "the action it added before it failed is gone")
        assertEquals(emptyList(), host.listening)
        assertTrue("broken unregistered" in Seen.log, "it is told, so that it can stop what it started")
    }

    @Test
    fun aClassOfAPluginThatIsNotThereIsExplained() {
        jar("linkage", "fixture/ext/linkage")
        extensions.load()
        val state = extensions.find("linkage")?.state as Extensions.State.Failed
        assertTrue("towny.object.Town" in state.reason && "not there" in state.reason, state.reason)
    }

    @Test
    fun anIdentifierCanBeUsedOnlyOnce() {
        jar("a-plain", "fixture/ext/plain")
        jar("b-clash", "fixture/ext/clash")
        assertEquals(1, extensions.load())
        val states = extensions.entries.map { it.file.name to it.state }
        assertEquals("a-plain.jar", states[0].first)
        assertEquals(Extensions.State.Registered, states[0].second)
        assertTrue((states[1].second as Extensions.State.Failed).reason.contains("also used"))
        assertEquals("1.2.3", extensions.find("plain")?.version, "the first one keeps the name")
    }

    @Test
    fun anIdentifierMustBeLowerCaseWords() {
        jar("badid", "fixture/ext/badid")
        extensions.load()
        val state = extensions.entries.single().state as Extensions.State.Failed
        assertTrue("not an identifier" in state.reason, state.reason)
    }

    @Test
    fun aNameCannotCarryAnotherIdentifier() {
        jar("badname", "fixture/ext/badname")
        extensions.load()
        val state = extensions.find("badname")?.state as Extensions.State.Failed
        assertTrue("isn't a name" in state.reason, state.reason)
        assertEquals(emptySet(), registry.externalNames)
    }

    @Test
    fun anExtensionThatCannotRegisterIsSkipped() {
        jar("skip", "fixture/ext/skip")
        assertEquals(0, extensions.load())
        assertEquals(Extensions.State.Skipped, extensions.find("skipped")?.state)
    }

    @Test
    fun aJarWithNoExtensionIsReported() {
        jar("empty", "fixture/ext/empty")
        Files.writeString(folder.resolve("broken.jar"), "this is not a jar")
        Files.writeString(folder.resolve("notes.txt"), "not a jar either")
        assertEquals(0, extensions.load())
        assertEquals(emptyList(), extensions.entries)
        assertEquals(setOf("empty.jar", "broken.jar"), extensions.problems.keys)
        assertTrue("MenuExtension" in extensions.problems.getValue("empty.jar"))
    }

    @Test
    fun theServicesFileNamesTheExtensions() {
        jar("twin", "fixture/ext/twin")
        extensions.load()
        assertEquals(setOf("first", "second"), extensions.entries.map { it.identifier }.toSet(), "without the file every class that is one is used")
        extensions.unload()

        jar("twin", "fixture/ext/twin", services = listOf("# the one we want", "fixture.ext.twin.Second", ""))
        extensions.load()
        assertEquals(listOf("second"), extensions.entries.map { it.identifier })
        extensions.unload()

        jar("twin", "fixture/ext/twin", services = listOf("fixture.ext.twin.Nope"))
        extensions.load()
        assertEquals(emptyList(), extensions.entries)
        assertTrue("Nope" in extensions.problems.getValue("twin.jar"))
    }

    @Test
    fun theLoaderOfAJarDoesNotSeeTheJarsOfOthers() {
        jar("plain", "fixture/ext/plain")
        jar("needy", "fixture/ext/needy")
        extensions.load()
        val loaders = extensions.entries.map { it.extension!!.javaClass.classLoader }
        assertEquals(2, loaders.distinct().size)
        assertFailsWith<ClassNotFoundException> {
            val plainLoader = extensions.find("plain")!!.extension!!.javaClass.classLoader
            Class.forName("fixture.ext.needy.Go", false, plainLoader)
        }
    }

    @Test
    fun aJarThatIsGoneIsNotLoadedAgain() {
        jar("plain", "fixture/ext/plain")
        extensions.load()
        extensions.unload()
        folder.resolve("plain.jar").deleteIfExists()
        assertEquals(0, extensions.load())
        folder.resolve("note.txt").writeText("x")
        assertEquals(emptyList(), extensions.entries)
    }
}
