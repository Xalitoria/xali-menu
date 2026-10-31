package mc.xalitoria.menu.paper.runtime

import com.typesafe.config.ConfigFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension
import kotlin.time.Duration
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.api.model.Item
import mc.xalitoria.menu.api.model.Menu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.util.verbatim
import mc.xalitoria.util.message.*
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType

/** A menu that has been read from its file, with its items sorted into the slots they claim. */
class LoadedMenu(val name: String, val menu: Menu) {
    /** Per slot, the items claiming it with the lowest priority number first, equal ones by name (a file has no order). */
    val bySlot: Map<Int, List<Pair<String, Item>>> = menu.items.entries
        .flatMap { entry -> entry.value.allSlots().map { it to (entry.key to entry.value) } }
        .groupBy({ it.first }, { it.second })
        .mapValues { entry -> entry.value.sortedWith(compareBy({ it.second.priority }, { it.first })) }
}

private fun Item.allSlots(): List<Int> = listOfNotNull(slot) + slots

/** The menus of `menus/`, and the players who have one open. */
class MenuManager(private val plugin: XaliMenu) {
    /** What is known of a menu file before it is fully read: the commands that open it. */
    private class Labels(val commands: List<String>, val register: Boolean)

    @Volatile
    private var loaded: Map<String, LoadedMenu> = emptyMap()

    @Volatile
    private var labelsOf: Map<String, Labels> = emptyMap()

    /** Whether [load] has run since the plugin enabled. */
    @Volatile
    var isLoaded = false
        private set
    private val views = ConcurrentHashMap<java.util.UUID, MenuView>()

    val names: List<String> get() = loaded.keys.sorted()

    val menus: Collection<LoadedMenu> get() = loaded.values

    fun menu(name: String): LoadedMenu? = loaded[name] ?: loaded.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun view(player: Player): MenuView? = views[player.uniqueId]

    private fun files() = plugin.dataPath.resolve("menus").takeIf { it.isDirectory() }?.toFile()
        ?.listFiles { f -> f.extension == "conf" }?.sortedBy { it.name }.orEmpty()

    /**
     * Reads just the commands of every menu file. Commands must be registered when the server starts, before the
     * plugins that add actions and conditions have all enabled, which a full read needs.
     */
    fun scan() {
        val found = mutableMapOf<String, Labels>()
        for (file in files()) {
            try {
                val config = ConfigFactory.parseFile(file)
                found[file.toPath().nameWithoutExtension] = Labels(
                    if (config.hasPath("commands")) config.getStringList("commands") else emptyList(),
                    !config.hasPath("register") || config.getBoolean("register"),
                )
            } catch (e: Exception) {
                plugin.logger fail "Menu '${file.nameWithoutExtension}' could not be read: ${e.message}"
            }
        }
        labelsOf = found
    }

    /** Reads every .conf file of the menus folder again. A file that can't be read is reported and left out; the rest still load. */
    fun load(): Int {
        scan()
        val format = MenuFormat(plugin.registry.module, plugin.registry.externalNames)
        val found = mutableMapOf<String, LoadedMenu>()
        for (file in files()) {
            val name = file.toPath().nameWithoutExtension
            try {
                found[name] = LoadedMenu(name, format.read(file.toPath()))
            } catch (e: Exception) {
                plugin.logger fail "Menu '$name' could not be read: ${e.message}".verbatim()
            }
        }
        clearTextCache()
        plugin.heads.clear()
        loaded = found
        isLoaded = true
        found.values.forEach(::check)
        return found.size
    }

    /** Notes in the console what is wrong with [entry], which would otherwise only show when a player opens it. */
    private fun check(entry: LoadedMenu) {
        val problems = mutableListOf<String>()
        val definition = entry.menu
        MenuArgs.problems(definition.args).forEach { problems += it }
        if (definition.type != "chest" && runCatching { InventoryType.valueOf(definition.type.uppercase()) }.isFailure) {
            problems += "inventory type '${definition.type}' doesn't exist"
        }
        if (definition.type == "chest" && (definition.size !in 9..54 || definition.size % 9 != 0)) {
            problems += "size ${definition.size} isn't 9, 18, ... 54"
        }
        for ((key, value) in definition.items) {
            if (value.allSlots().isEmpty()) problems += "item '$key' has no slot, so it is never shown"
            if (definition.type == "chest") {
                value.allSlots().filter { it !in 0 until definition.size }.forEach { problems += "item '$key' is in slot $it, outside the menu" }
            }
            plugin.items.validate(value).forEach { problems += "item '$key': $it" }
        }
        val actions = definition.onOpen + definition.onClose + definition.items.values.flatMap { item ->
            val clicks = item.click
            listOfNotNull(clicks.any, clicks.left, clicks.right, clicks.shiftLeft, clicks.shiftRight, clicks.middle).flatMap { it.actions }
        }
        actions.filterIsInstance<Actions.Open>().filter { menu(it.menu) == null }.forEach {
            problems += "opens the menu '${it.menu}', which doesn't exist"
        }
        problems.forEach { plugin.logger warn "Menu '${entry.name}': $it" }
    }

    /**
     * Opens [name] for [player] on the next tick of the player's region. [args] are the words after the command; a menu
     * that names arguments needs at least that many.
     */
    fun open(player: Player, name: String, args: List<String> = emptyList()) {
        val target = menu(name)
        if (target == null) {
            player fail plugin.messageConfig.data.commands.menu.unknown(name)
            return
        }
        player.scheduler.run(plugin, { open(player, target, args) }, null)
    }

    private fun open(player: Player, target: LoadedMenu, args: List<String>) {
        val menu = target.menu
        val named = MenuArgs.bind(menu.args, args)
        if (named == null) {
            player fail plugin.messageConfig.data.commands.menu.usage(menu.commands.firstOrNull() ?: target.name, menu.argsUsage ?: MenuArgs.usage(menu.args))
            return
        }
        val ctx = Context(player, target.name, named, plugin.actions)

        if (!menu.open.passes(ctx, run = true)) return

        val view = MenuView(plugin, target.name, menu, ctx, target.bySlot)
        val title = ctx.component(menu.title)
        val inventory = if (menu.type == "chest") {
            Bukkit.createInventory(view, menu.size.coerceIn(9, 54) / 9 * 9, title)
        } else {
            Bukkit.createInventory(view, runCatching { InventoryType.valueOf(menu.type.uppercase()) }.getOrDefault(InventoryType.CHEST), title)
        }
        view.attach(inventory)
        view.draw()

        // Registered first, so closing the menu this player had open isn't taken for the end of this one.
        views.put(player.uniqueId, view)?.stopRefresh()
        if (player.openInventory(inventory) == null) {
            // Another plugin canceled it.
            views.remove(player.uniqueId, view)
            return
        }
        menu.refresh?.let { view.startRefresh(ticks(it)) }
        debug("{0} opened {1}", player.name, target.name)
        plugin.actions.run(menu.onOpen, ctx)
    }

    /** The player closed [view], rather than it being replaced by another menu. */
    fun closed(player: Player, view: MenuView) {
        if (!views.remove(player.uniqueId, view)) return
        view.stopRefresh()
        plugin.actions.run(view.menu.onClose, view.ctx)
    }

    /** The player left; there is nobody to run the close actions for. */
    fun left(player: Player) {
        views.remove(player.uniqueId)?.stopRefresh()
    }

    /** Closes every open menu, for when the plugin goes away. */
    fun closeAll() {
        for (id in views.keys.toList()) {
            val view = views.remove(id) ?: continue
            view.stopRefresh()
            runCatching { view.player.closeInventory() }
        }
    }

    private fun ticks(duration: Duration): Long = (duration.inWholeMilliseconds / 50).coerceAtLeast(1)


    /**
     * The labels players can type to open menus, with the menu each opens. A label of one word is registered as a command
     * unless its menu says `register = false`; [intercepted] are the rest.
     */
    fun labels(): Map<String, String> = labelsOf.entries.flatMap { entry -> entry.value.commands.map { it.lowercase() to entry.key } }.toMap()

    fun registered(): Map<String, String> {
        val labels = labelsOf
        return this.labels().filter { entry -> labels.getValue(entry.value).register && ' ' !in entry.key }
    }

    fun intercepted(): Map<String, String> = labels() - registered().keys
}
