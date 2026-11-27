package mc.xalitoria.menu.paper.extension

import mc.xalitoria.menu.paper.api.TypeRegistry
import net.xalitoria.menu.ActionType
import net.xalitoria.menu.ActivatorSource
import net.xalitoria.menu.ActivatorType
import net.xalitoria.menu.CatalogType
import net.xalitoria.menu.ConditionType
import net.xalitoria.menu.ExtensionContext
import net.xalitoria.menu.ItemPropertyType
import net.xalitoria.menu.ItemSource
import net.xalitoria.menu.MenuExtension
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * The extensions: jars in the extensions folder with a class that extends [MenuExtension], which add actions, conditions,
 * catalogs, activators and item sources for plugins that do not know XaliMenu. Each is read once, waits for the plugins it needs,
 * registers what it offers in the [TypeRegistry] under an owner of its own, and takes it all away again when it goes.
 *
 * The server is behind [Host], so that the rules run without one.
 */
class Extensions(private val host: Host, private val registry: TypeRegistry) {
    /** What the extensions need from the server. */
    interface Host {
        /** XaliMenu, which owns the tasks and listeners of every extension. */
        val plugin: Plugin

        /** Where the jars are, and the folders of the extensions' own files. */
        val folder: Path

        val logger: Logger

        /** The loader that has XaliMenu's classes, its libraries and Bukkit. */
        val parent: ClassLoader

        /** The plugins that are enabled other than XaliMenu, by lower case name, with their class loaders. */
        fun enabledPlugins(): Map<String, ClassLoader>

        fun listen(listener: Listener)

        fun unlisten(listener: Listener)

        /** Tells the menus that [player] did what the activator [name] stands for. */
        fun fire(name: String, player: Player, values: Map<String, String>): Boolean

        /** Closes [loader] once nothing is likely to be using its classes: later on a running server, now when it is stopping. */
        fun release(loader: AutoCloseable)
    }

    /** Where an extension is. */
    sealed interface State {
        data object Registered : State

        /** Not registered yet because the plugins [plugins] are not enabled. */
        data class Waiting(val plugins: List<String>) : State

        /** [MenuExtension.canRegister] said no. */
        data object Skipped : State

        data class Failed(val reason: String) : State
    }

    /** One extension that was found in [file]. [extension] is null when its class could not be created, which [state] then says. */
    class Entry internal constructor(val file: Path, val extension: MenuExtension?, val identifier: String, internal val loader: ExtensionClassLoader) {
        @Volatile
        var state: State = State.Waiting(emptyList())
            internal set

        /** The extension's version, or `?` when it cannot say. */
        val version: String get() = safe { extension?.version }

        /** The extension's author, or `?` when it cannot say. */
        val author: String get() = safe { extension?.author }

        /** What the extension's registrations are kept under. */
        internal val owner = Any()
        internal val listeners = ArrayList<Listener>()
    }

    private val lock = Any()

    @Volatile
    private var found: List<Entry> = emptyList()

    @Volatile
    private var broken: Map<String, String> = emptyMap()

    /** The extensions that were found, in the order of their jars. */
    val entries: List<Entry> get() = found

    /** The jars that could not be used (`file.jar` to why), because they could not be opened or hold no extension. */
    val problems: Map<String, String> get() = broken

    fun find(identifier: String): Entry? = found.firstOrNull { it.identifier == identifier }

    /** What [entry] added, as `kind name`. */
    fun added(entry: Entry): List<String> = registry.addedBy(entry.owner)

    /**
     * Reads the jars of the folder and registers what can be registered now. The folder is made when it is not there. Call [unload]
     * first when there are extensions already. @return how many are registered
     */
    fun load(): Int = synchronized(lock) {
        check(found.isEmpty() && broken.isEmpty()) { "The extensions are loaded; unload them first" }
        if (!host.folder.isDirectory()) {
            runCatching { Files.createDirectories(host.folder) }
            return 0
        }
        val entries = ArrayList<Entry>()
        val problems = LinkedHashMap<String, String>()
        val jars = Files.list(host.folder).use { stream -> stream.filter { it.extension == "jar" && !it.isDirectory() }.sorted().toList() }
        for (jar in jars) {
            val loader = ExtensionClassLoader(jar, host.parent) { host.enabledPlugins().values }
            val read = try {
                read(jar, loader)
            } catch (e: Exception) {
                problems[jar.name] = e.message ?: e::class.java.simpleName
                emptyList()
            }
            if (read.isEmpty()) {
                loader.close()
                if (jar.name !in problems) problems[jar.name] = "it holds no class that extends MenuExtension"
                continue
            }
            entries += read.map { it.build(jar, loader) }
        }
        val taken = HashSet<String>()
        for (entry in entries) {
            if (!taken.add(entry.identifier) && entry.state !is State.Failed) entry.state = State.Failed("the identifier '${entry.identifier}' is also used by another extension")
        }
        broken = problems
        found = entries
        entries.forEach(::attempt)
        entries.count { it.state == State.Registered }
    }

    /** Unregisters every extension and lets go of the jars. */
    fun unload() = synchronized(lock) {
        for (entry in found.asReversed()) retire(entry)
        val loaders = found.map { it.loader }.distinct()
        found = emptyList()
        broken = emptyMap()
        loaders.forEach(host::release)
    }

    /** Registers the extensions that were waiting for [plugin], which has just been enabled. @return whether any was registered */
    fun pluginEnabled(plugin: String): Boolean = synchronized(lock) {
        var changed = false
        for (entry in found) {
            val waiting = entry.state as? State.Waiting ?: continue
            if (waiting.plugins.none { it.equals(plugin, ignoreCase = true) }) continue
            attempt(entry)
            changed = changed or (entry.state == State.Registered)
        }
        changed
    }

    /** Takes away what the extensions that need [plugin] added, which is being disabled. They wait for it again. @return whether any was taken away */
    fun pluginDisabled(plugin: String): Boolean = synchronized(lock) {
        var changed = false
        for (entry in found) {
            if (entry.state != State.Registered) continue
            if (!requires(entry, plugin)) continue
            retire(entry)
            entry.state = State.Waiting(listOf(plugin))
            changed = true
        }
        changed
    }

    private fun requires(entry: Entry, plugin: String): Boolean = try {
        entry.extension?.requiredPlugins.orEmpty().any { it.equals(plugin, ignoreCase = true) }
    } catch (e: Exception) {
        false
    }

    /** What the jar holds that [MenuExtension] is the parent of: those named in `META-INF/services`, or else every class that is one. */
    private fun read(jar: Path, loader: ExtensionClassLoader): List<Candidate> {
        var listed = true
        val names = JarFile(jar.toFile()).use { file ->
            file.getJarEntry(SERVICES)?.let { entry ->
                file.getInputStream(entry).bufferedReader().readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            } ?: run {
                listed = false
                file.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") && '$' !in it && !it.startsWith("META-INF/") && it != "module-info.class" }
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .toList()
            }
        }
        val candidates = ArrayList<Candidate>()
        for (name in names) {
            val type = try {
                Class.forName(name, false, loader)
            } catch (e: LinkageError) {
                // A class of a shaded library or of a hook that needs a plugin that is not here is not an extension.
                host.logger.fine("Extension jar ${jar.name}: $name could not be loaded: $e")
                continue
            } catch (e: ClassNotFoundException) {
                if (listed) throw IllegalArgumentException("META-INF/services names $name, which is not in the jar")
                continue
            }
            if (!MenuExtension::class.java.isAssignableFrom(type) || type.isInterface || Modifier.isAbstract(type.modifiers)) continue
            candidates += Candidate(type.asSubclass(MenuExtension::class.java))
        }
        return candidates
    }

    private inner class Candidate(private val type: Class<out MenuExtension>) {
        /** The entry for the instance; one that cannot be made is an entry that failed, so it is listed. */
        fun build(jar: Path, loader: ExtensionClassLoader): Entry {
            val instance = try {
                type.getDeclaredConstructor().newInstance()
            } catch (e: Exception) {
                return failed(jar, loader, "${type.name} could not be created: ${cause(e)}")
            } catch (e: LinkageError) {
                return failed(jar, loader, "${type.name} could not be created: ${linkage(e)}")
            }
            val identifier = try {
                instance.identifier
            } catch (e: Exception) {
                return failed(jar, loader, "${type.name} has no identifier: ${cause(e)}")
            }
            val entry = Entry(jar, instance, identifier, loader)
            if (!IDENTIFIER.matches(identifier)) entry.state = State.Failed("'$identifier' is not an identifier; use lower case letters, digits and _ - .")
            return entry
        }

        private fun failed(jar: Path, loader: ExtensionClassLoader, reason: String): Entry =
            Entry(jar, null, type.name, loader).also { it.state = State.Failed(reason) }
    }

    /** Moves [entry] to the state its requirements allow, registering it when they are met. */
    private fun attempt(entry: Entry) {
        if (entry.state is State.Failed) return
        val extension = entry.extension ?: return
        var began = false
        try {
            val enabled = host.enabledPlugins()
            val missing = extension.requiredPlugins.filter { it.lowercase() !in enabled }
            if (missing.isNotEmpty()) {
                entry.state = State.Waiting(missing)
                return
            }
            if (!extension.canRegister()) {
                entry.state = State.Skipped
                return
            }
            began = true
            extension.register(Context(entry))
            entry.state = State.Registered
            host.logger.info("Extension ${entry.identifier} ${entry.version} by ${entry.author} registered.")
        } catch (e: Exception) {
            fail(entry, cause(e), e, began)
        } catch (e: LinkageError) {
            fail(entry, linkage(e), e, began)
        }
    }

    /** [began] says whether the extension's `register` was started, in which case it is stopped too. */
    private fun fail(entry: Entry, reason: String, error: Throwable, began: Boolean) {
        if (began) retire(entry) else registry.removeAll(entry.owner)
        entry.state = State.Failed(reason)
        host.logger.log(Level.WARNING, "Extension ${entry.identifier} could not be registered: $reason", error)
    }

    /** Stops [entry], then takes away everything it added. */
    private fun retire(entry: Entry) {
        try {
            entry.extension?.unregister()
        } catch (e: Exception) {
            host.logger.log(Level.WARNING, "Extension ${entry.identifier} threw while it was taken away", e)
        } catch (e: LinkageError) {
            host.logger.log(Level.WARNING, "Extension ${entry.identifier} threw while it was taken away", e)
        }
        registry.removeAll(entry.owner)
        entry.listeners.forEach { runCatching { host.unlisten(it) } }
        entry.listeners.clear()
    }

    private fun cause(e: Exception): String = (e.cause?.takeIf { e is java.lang.reflect.InvocationTargetException } ?: e).let { it.message ?: it::class.java.simpleName }

    private fun linkage(e: LinkageError): String = when (e) {
        is NoClassDefFoundError -> "it uses ${e.message?.replace('/', '.')}, which is not there (is the plugin it is for installed?)"
        else -> e.toString()
    }

    private inner class Context(private val entry: Entry) : ExtensionContext {
        private val id = entry.identifier

        override val plugin: Plugin get() = host.plugin

        override val logger: Logger = Prefixed(host.logger, id)

        override val dataFolder: File get() = host.folder.resolve(id).toFile()

        private fun named(name: String): String {
            require(NAME.matches(name)) { "'$name' isn't a name; use lower case letters, digits and _ - . / without the identifier, such as reward" }
            return "$id:$name"
        }

        override fun registerAction(name: String, type: ActionType) = registry.addAction(entry.owner, named(name), type)

        override fun registerCondition(name: String, type: ConditionType) = registry.addCondition(entry.owner, named(name), type)

        override fun registerCatalog(name: String, type: CatalogType) = registerCatalog(name, emptyMap(), emptyMap(), type)

        override fun registerCatalog(name: String, values: Map<String, String>, type: CatalogType) = registerCatalog(name, values, emptyMap(), type)

        override fun registerCatalog(name: String, values: Map<String, String>, settings: Map<String, String>, type: CatalogType) =
            registry.addCatalog(entry.owner, named(name), type, values, settings)

        override fun registerActivator(name: String, type: ActivatorType): ActivatorSource {
            val full = named(name)
            registry.addActivator(entry.owner, full, type)
            return ActivatorSource { player, values -> host.fire(full, player, values) }
        }

        override fun registerItemSource(source: ItemSource) = registry.addSource(entry.owner, source)

        override fun registerItemProperty(name: String, type: ItemPropertyType) = registerItemProperty(name, emptyMap(), type)

        override fun registerItemProperty(name: String, settings: Map<String, String>, type: ItemPropertyType) =
            registry.addItemProperty(entry.owner, named(name), type, settings)

        override fun registerListener(listener: Listener) {
            host.listen(listener)
            entry.listeners += listener
        }
    }

    /** A logger named after the extension, which the server's console shows in front of each line, and that logs through the plugin's. */
    private class Prefixed(private val target: Logger, id: String) : Logger(id, null) {
        init {
            level = Level.ALL
        }

        override fun log(record: LogRecord) = target.log(record)
    }

    private companion object {
        private inline fun safe(read: () -> String?): String = try {
            read() ?: "?"
        } catch (e: Exception) {
            "?"
        }

        const val SERVICES = "META-INF/services/net.xalitoria.menu.MenuExtension"

        /** What an extension calls itself. */
        val IDENTIFIER = Regex("[a-z0-9_.-]+")

        /** What an extension calls one of its actions, conditions, catalogs and activators, before the identifier is put in front. */
        val NAME = Regex("[a-z0-9_./-]+")
    }
}
