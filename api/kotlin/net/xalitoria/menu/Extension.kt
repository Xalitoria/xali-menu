package net.xalitoria.menu

import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.logging.Logger

/**
 * A jar in `plugins/XaliMenu/extensions` that adds actions, conditions, catalogs, activators, item sources and item
 * properties for a plugin that knows nothing about XaliMenu, the way a PlaceholderAPI expansion adds placeholders. The
 * extension is not a plugin: XaliMenu finds the class, creates it, and calls [register] once every plugin has enabled
 * and the plugins in [requiredPlugins] are among them. An extension jar is code that runs with the server's rights, as
 * a plugin does; only add jars you trust.
 *
 * Write one class that extends this and give the jar nothing else to declare:
 *
 * ```kotlin
 * class TownyExtension : MenuExtension() {
 *     override val identifier = "towny"
 *     override val author = "me"
 *     override val version = "1.0.0"
 *     override val requiredPlugins = listOf("Towny")
 *
 *     override fun register(context: ExtensionContext) {
 *         context.registerCondition("resident") { settings -> InTown(settings.string("town")) }     // { type = "towny:resident" }
 *         context.registerAction("spawn") { settings -> TownSpawn(settings.requireString("town")) }   // { type = "towny:spawn", town = "x" }
 *     }
 * }
 * ```
 *
 * ```java
 * public final class TownyExtension extends MenuExtension {
 *     @Override public String getIdentifier() { return "towny"; }
 *     @Override public String getAuthor() { return "me"; }
 *     @Override public String getVersion() { return "1.0.0"; }
 *     @Override public List<String> getRequiredPlugins() { return List.of("Towny"); }
 *     @Override public void register(ExtensionContext context) {
 *         context.registerCondition("resident", settings -> new InTown(settings.getString("town")));
 *     }
 * }
 * ```
 *
 * The class needs a public constructor with no parameters and must not mention the classes of the required plugins in its
 * fields, its signatures or its constructor: put the code that uses them in other classes, which are only loaded when [register]
 * calls them. Compile against the API of XaliMenu and the other plugins as `compileOnly`, and do not put either, nor the Kotlin
 * runtime, into the jar: XaliMenu's own copies are the ones the extension uses. Another plugin's classes are found by name
 * among the plugins that are enabled, so the extension needs no entry in a `plugin.yml`.
 *
 * What an extension adds is named `identifier:name`, so two extensions cannot meet, and it goes when the extension does.
 * A jar may hold several extensions. The same rules as for addons hold for what is registered (the actions run on the
 * player's region thread; conditions, catalogs and item properties must be cheap); see [Action], [Condition], [Catalog]
 * and [ItemProperty]. Values read from [Settings] are raw text from the menu file: placeholders and `{name}` values
 * (from menu `args` or a paged catalog) are not filled in for you, so call [MenuContext.parse] on any setting that may
 * contain them when your action, condition or item property runs.
 */
abstract class MenuExtension {
    /**
     * What comes before the colon in every name the extension adds, and the name of its data folder: lower case letters, digits
     * and `_ - .`, such as `towny`. It must be the same on every start and differ from every other extension's.
     */
    abstract val identifier: String

    /** Who wrote the extension; shown by `/xmenu extensions`. */
    abstract val author: String

    /** The version of the extension; shown by `/xmenu extensions`. */
    abstract val version: String

    /**
     * The plugins that must be enabled for [register] to run, by name. The extension waits for them, is taken away when one of
     * them is disabled, and is registered again when it is enabled. The default is none.
     */
    open val requiredPlugins: List<String> get() = emptyList()

    /**
     * Whether the extension can register now, for a reason [requiredPlugins] cannot say (a setting, a version of the other
     * plugin). It is asked after the required plugins are found. When it returns false the extension is listed as skipped and
     * asked again on `/xmenu extensions reload`. The default is true.
     */
    open fun canRegister(): Boolean = true

    /**
     * Adds what the extension offers through [context]. It is called on the global thread when the menus are about to be read.
     * If it throws, whatever it had added is taken away and the extension is listed as failed with the message.
     */
    abstract fun register(context: ExtensionContext)

    /**
     * Called when the extension is taken away, before what [register] added goes, so that it can stop what it started on its own
     * (a task, a connection). What was added through the [ExtensionContext] does not need to be undone here. The default does
     * nothing.
     */
    open fun unregister() {}
}

/**
 * What a [MenuExtension] registers with, given to [MenuExtension.register]. Everything added here is named
 * `<identifier>:<name>`, with the extension's own identifier in front of the name that is passed, and goes when the extension
 * does. Names are lower case letters, digits and `_ - . /`.
 *
 * Use this and not [XaliMenuApi] for what is added. The API is still there for the rest (`XaliMenuApi.get()` opens menus and
 * reaches the variables).
 */
interface ExtensionContext {
    /** XaliMenu, which owns the extension's tasks and listeners. Use it where Bukkit asks for a plugin, such as the schedulers. */
    val plugin: Plugin

    /** A logger whose lines are marked with the extension's identifier. */
    val logger: Logger

    /** `plugins/XaliMenu/extensions/<identifier>`, for the extension's own files. The folder is not created until it is needed. */
    val dataFolder: File

    /** Adds the action `<identifier>:<name>`, built from the keys next to `type`, as [XaliMenuApi.registerAction] does. */
    fun registerAction(name: String, type: ActionType)

    /** Adds the condition `<identifier>:<name>`, as [XaliMenuApi.registerCondition] does. */
    fun registerCondition(name: String, type: ConditionType)

    /** Adds the catalog `<identifier>:<name>`, as [XaliMenuApi.registerCatalog] does. */
    fun registerCatalog(name: String, type: CatalogType)

    /** Adds the catalog `<identifier>:<name>` and says what its elements hold, as [XaliMenuApi.registerCatalog] does. */
    fun registerCatalog(name: String, values: Map<String, String>, type: CatalogType)

    /** Adds the catalog `<identifier>:<name>` and says what its elements and settings are, as [XaliMenuApi.registerCatalog] does. */
    fun registerCatalog(name: String, values: Map<String, String>, settings: Map<String, String>, type: CatalogType)

    /**
     * Adds the activator `<identifier>:<name>`, as [XaliMenuApi.registerActivator] does. Call the returned source from a listener
     * of the extension (see [registerListener]) when the player has done what the activator stands for.
     */
    fun registerActivator(name: String, type: ActivatorType): ActivatorSource

    /** Adds an item source. Its [ItemSource.prefix] is its own (`mmoitems`), not prefixed with the identifier, and cannot be one that is taken. */
    fun registerItemSource(source: ItemSource)

    /** Adds the item property `<identifier>:<name>`, as [XaliMenuApi.registerItemProperty] does. */
    fun registerItemProperty(name: String, type: ItemPropertyType)

    /** Adds the item property `<identifier>:<name>` and says what its settings are, as [XaliMenuApi.registerItemProperty] does. */
    fun registerItemProperty(name: String, settings: Map<String, String>, type: ItemPropertyType)

    /**
     * Registers [listener] for the extension. It is registered with [plugin] and unregistered when the extension goes. Event
     * classes of the required plugins are found as the extension's other classes are.
     */
    fun registerListener(listener: Listener)
}
