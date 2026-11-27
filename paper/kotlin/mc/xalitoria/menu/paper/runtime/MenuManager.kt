package mc.xalitoria.menu.paper.runtime

import com.typesafe.config.ConfigFactory
import com.xalitoria.util.message.*
import com.xalitoria.util.message.verbatim
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kotlinx.coroutines.launch
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.XaliMenu.Companion.debug
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.action.Flow
import mc.xalitoria.menu.paper.action.Social
import mc.xalitoria.menu.paper.bedrock.BedrockPlanner
import mc.xalitoria.menu.paper.bedrock.Presentation
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.util.batched
import net.xalitoria.menu.Action
import net.xalitoria.menu.ActivatorFilter
import net.xalitoria.menu.Settings
import net.xalitoria.menu.model.Activator
import net.xalitoria.menu.model.BedrockField
import net.xalitoria.menu.model.BedrockMenu
import net.xalitoria.menu.model.Gate
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Menu
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A menu that has been read from its file, with its items sorted into the slots they claim. */
class LoadedMenu(val name: String, val menu: Menu, val usesVariables: Boolean = false, val warnings: List<String> = []) {
    /** Per slot, the items claiming it with the lowest priority number first, equal ones by name (a file has no order). */
    val bySlot: Map<Int, List<Pair<String, Item>>> = slotted(menu.placed())

    /** The same for the items drawn in the player's own inventory, by the slot of that inventory. */
    val inventoryBySlot: Map<Int, List<Pair<String, Item>>> = slottedInventory(menu).filterKeys { it in Menu.INVENTORY_SLOTS }
}

/** [items] sorted into the slots they claim: per slot, lowest priority number first, equal ones by name (a file has no order). */
fun slotted(items: Map<String, Item>): Map<Int, List<Pair<String, Item>>> = items.entries
    .flatMap { entry -> entry.value.claimed().map { it to (entry.key to entry.value) } }
    .groupBy({ it.first }, { it.second })
    .mapValues { entry -> entry.value.sortedWith(compareBy({ it.second.priority }, { it.first })) }

/** The items [menu] draws in the player's own inventory, sorted into the slots of that inventory by their `inventory-slots`, like [slotted] does for the menu's. */
fun slottedInventory(menu: Menu): Map<Int, List<Pair<String, Item>>> = menu.items.entries
    .flatMap { entry -> entry.value.inventorySlots.distinct().map { it to (entry.key to entry.value) } }
    .groupBy({ it.first }, { it.second })
    .mapValues { entry -> entry.value.sortedWith(compareBy({ it.second.priority }, { it.first })) }

/** Whether the file of a menu names a variable placeholder (`%xmenu_var_coins%`), so that the menu has something to show when one changes. */
internal fun showsVariables(file: java.nio.file.Path): Boolean = try {
    showsVariables(java.nio.file.Files.readString(file))
} catch (e: java.io.IOException) {
    false
}

internal fun showsVariables(text: String): Boolean = VARIABLE_PLACEHOLDER.containsMatchIn(text)

private val VARIABLE_WINDOW = 100.milliseconds

private val VARIABLE_PLACEHOLDER = Regex("%xmenu_var(p|t|pt)?_", RegexOption.IGNORE_CASE)

/** The menus of `menus/`, and the players who have one open. */
class MenuManager(private val plugin: XaliMenu) {
    /** What is known of a menu file before it is fully read: the commands that open it. */
    private class Labels(val commands: List<String>, val register: Boolean)

    @Volatile
    private var loaded: Map<String, LoadedMenu> = emptyMap()

    @Volatile
    private var labelsOf: Map<String, Labels> = emptyMap()

    /** Why the menu files that could not be read were left out, by menu name, as of the last load. */
    @Volatile
    var failures: Map<String, String> = emptyMap()
        private set

    /** Whether [load] has run since the plugin enabled. */
    @Volatile
    var isLoaded = false
        private set
    private val views = ConcurrentHashMap<java.util.UUID, MenuView>()

    /** What the players can do to open the loaded menus: their `open-with`. */
    @Volatile
    var activators: ActivatorIndex = ActivatorIndex.EMPTY
        private set

    val names: List<String> get() = loaded.keys.sorted()

    val menus: Collection<LoadedMenu> get() = loaded.values

    fun menu(name: String): LoadedMenu? = loaded[name] ?: loaded.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun view(player: Player): MenuView? = views[player.uniqueId]

    /**
     * Redraws the open menus that show variables when one of them changes, so an item marked `update` shows the new value at once and not at
     * the next `refresh` (or never, for a menu with none). The changes are gathered for a moment, so a burst is one redraw; a personal
     * variable redraws that player's menu only. Each redraw runs on the player's own thread.
     */
    internal fun watchVariables(changes: kotlinx.coroutines.flow.Flow<mc.xalitoria.menu.paper.variable.Key>, scope: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.Job =
        scope.launch {
            changes.batched(VARIABLE_WINDOW).collect { keys ->
                val global = keys.any { it.owner == mc.xalitoria.menu.paper.variable.VariableStore.GLOBAL }
                val owners = keys.mapTo(HashSet()) { it.owner }
                for (entry in views) {
                    if (loaded[entry.value.name]?.usesVariables != true) continue
                    if (global || entry.key.toString() in owners) entry.value.variablesChanged()
                }
            }
        }

    private fun files() = plugin.dataPath.resolve("menus").takeIf { it.isDirectory() }?.toFile()
        ?.listFiles { f -> f.extension == "conf" }?.sortedBy { it.name }.orEmpty()

    /**
     * Reads only the commands of every menu file. Commands must be registered when the server starts, before the
     * plugins that add actions and conditions have all enabled, which a full read needs.
     */
    fun scan() {
        val found = mutableMapOf<String, Labels>()
        for (file in files()) {
            try {
                val config = ConfigFactory.parseFile(file, MenuFormat.SAFE_PARSE)
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

    /** Reads every .conf file of the menus folder again. A file that cannot be read is reported and left out; the rest still load. */
    fun load(): Int {
        scan()
        val format = MenuFormat(plugin.registry.module, plugin.registry.externalNames, plugin.registry.activatorNames, plugin.registry.propertyNames)
        val found = mutableMapOf<String, LoadedMenu>()
        val failed = mutableMapOf<String, String>()
        for (file in files()) {
            val name = file.toPath().nameWithoutExtension
            try {
                val read = format.readChecked(file.toPath())
                found[name] = LoadedMenu(name, read.menu, showsVariables(file.toPath()), read.warnings.map { it.toString() })
            } catch (e: Exception) {
                failed[name] = e.message ?: e::class.simpleName.orEmpty()
                plugin.logger fail "Menu '$name' could not be read: ${e.message}".verbatim()
            }
        }
        clearTextCache()
        failures = failed
        loaded = found
        activators = ActivatorIndex(found.mapValues { it.value.menu.openWith }, ::filterOf)
        isLoaded = true
        found.values.forEach(::check)
        return found.size
    }

    /** What reading the file of one menu again came to. */
    sealed interface Reread {
        /** The menu is in place; [problems] are what [problemsOf] finds in it. */
        data class Done(val problems: List<String>) : Reread

        data object NoFile : Reread

        data class Failed(val reason: String) : Reread
    }

    /**
     * Reads the file of the menu [name] again and puts it in place of the one loaded, leaving the other menus alone. A file
     * that cannot be read leaves the menu as it was. Commands are registered at startup, so a changed `commands` needs a restart.
     */
    fun reload(name: String): Reread {
        val file = files().firstOrNull { it.nameWithoutExtension.equals(name, ignoreCase = true) } ?: return Reread.NoFile
        val key = file.nameWithoutExtension
        val entry = try {
            val read = MenuFormat(plugin.registry.module, plugin.registry.externalNames, plugin.registry.activatorNames, plugin.registry.propertyNames).readChecked(file.toPath())
            LoadedMenu(key, read.menu, showsVariables(file.toPath()), read.warnings.map { it.toString() })
        } catch (e: Exception) {
            failures += key to (e.message ?: e::class.simpleName.orEmpty())
            return Reread.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
        clearTextCache()
        failures -= key
        val menus = loaded + (key to entry)
        loaded = menus
        activators = ActivatorIndex(menus.mapValues { it.value.menu.openWith }, ::filterOf)
        scan()
        return Reread.Done(problemsOf(entry).also { found -> found.forEach { plugin.logger warn "Menu '$key': $it" } })
    }

    /** Notes in the console what is wrong with [entry], which would otherwise only show when a player opens it. */
    private fun check(entry: LoadedMenu) = problemsOf(entry).forEach { plugin.logger warn "Menu '${entry.name}': $it" }

    /** What is wrong with [entry] that does not depend on who opens it. */
    fun problemsOf(entry: LoadedMenu): List<String> {
        val problems = entry.warnings.toMutableList()
        val definition = entry.menu
        MenuArgs.problems(definition.args).forEach { problems += it }
        MenuArgs.unknown(definition.args, definition.complete).forEach { problems += "complete gives words for '$it', which isn't one of the args" }
        if (definition.type != "chest") {
            val resolvedType = runCatching { InventoryType.valueOf(definition.type.uppercase()) }.getOrNull()
            if (resolvedType == null) {
                problems += "inventory type '${definition.type}' doesn't exist"
            } else if (!MenuClicks.isSafeContainerType(resolvedType.name)) {
                problems += "inventory type '${definition.type}' has vanilla slot/recipe logic; prefer chest, hopper, dispenser, dropper, shulker_box or barrel"
            }
        }
        if (definition.type == "chest" && (definition.size !in 9..54 || definition.size % 9 != 0)) {
            problems += "size ${definition.size} isn't 9, 18, ... 54"
        }
        definition.matrix?.let { matrix ->
            if (matrix.cells.size * 9 > definition.size && definition.type == "chest") {
                problems += "matrix.cells has ${matrix.cells.size} rows, more than the menu"
            }
        }
        for ((key, value) in definition.placed()) {
            if (value.claimed().isEmpty() && value.inventorySlots.isEmpty()) problems += "item '$key' has no slot, so it is never shown"
            value.inventorySlots.filter { it !in Menu.INVENTORY_SLOTS }.forEach { problems += "item '$key' has inventory slot $it, outside the inventory (0 to 35)" }
            if (definition.type == "chest") {
                value.claimed().filter { it !in 0 until definition.size }.forEach { problems += "item '$key' is in slot $it, outside the menu" }
            }
            plugin.items.validate(value).forEach { problems += "item '$key': $it" }
            acceptProblems(definition, value).forEach { problems += "item '$key' $it" }
        }
        definition.pages?.let { pages ->
            pages.listed().filter { definition.type == "chest" && it !in 0 until definition.size }.forEach { problems += "pages has slot $it, outside the menu" }
            val rows = maxOf(pages.layout.size, pages.matrix?.cells?.size ?: 0)
            if (rows * 9 > definition.size && definition.type == "chest") problems += "pages.${if (pages.matrix != null) "matrix.cells" else "layout"} has $rows rows, more than the menu"
            pages.matrix?.let { matrix ->
                val used = matrix.cells.flatMap { it.toList() }.map { it.toString() }.toSet()
                matrix.templates.keys.filter { it !in used }.forEach { problems += "matrix.templates has '$it', which stands in no cell of matrix.cells" }
            }
            val templates = pages.matrix?.templates?.map { [char, item] -> "matrix.templates.$char" to item }.orEmpty()
            (listOfNotNull(pages.item?.let { "item" to it }) + templates + listOfNotNull(pages.empty?.let { "empty" to it })).forEach { [label, item] ->
                if (label != "empty" && (item.claimed().isNotEmpty())) problems += "the slot of pages.$label is not used; its place comes from where the elements go"
                plugin.items.validate(item).forEach { problems += "pages.$label: $it" }
                acceptProblems(definition, item).forEach { problems += "pages.$label $it" }
            }
        }
        definition.openWith.forEachIndexed { number, activator ->
            val label = "open-with ${number + 1}"
            activatorProblems(activator).forEach { problems += "$label: $it" }
            if (MenuArgs.bind(definition.args, activator.args) == null) {
                problems += "$label: gives ${activator.args.size} words, but the menu's args are ${definition.args}"
            }
        }
        definition.animation?.frames?.forEachIndexed { number, frame ->
            for ([key, item] in frame.items) {
                if (item.claimed().isEmpty()) problems += "frame ${number + 1}: item '$key' has no slot, so it is never shown"
                if (definition.type == "chest") item.claimed().filter { it !in 0 until definition.size }.forEach { problems += "frame ${number + 1}: item '$key' is in slot $it, outside the menu" }
                plugin.items.validate(item).forEach { problems += "frame ${number + 1}: item '$key': $it" }
                acceptProblems(definition, item).forEach { problems += "frame ${number + 1}: item '$key' $it" }
            }
        }
        bedrockProblems(entry).forEach { problems += it }
        allActionsOf(definition).filterIsInstance<Actions.Open>().filter { menu(it.menu) == null }.forEach {
            problems += "opens the menu '${it.menu}', which doesn't exist"
        }
        return problems
    }

    /** Validates the `bedrock` section of [entry], if present. */
    private fun bedrockProblems(entry: LoadedMenu): List<String> = buildList {
        val cfg = entry.menu.bedrock ?: return@buildList
        cfg.menu?.takeIf { it.isNotBlank() }?.let { target ->
            if (target.equals(entry.name, ignoreCase = true)) add("bedrock.menu points to '${entry.name}' itself")
            else if (menu(target) == null) add("bedrock.menu points to '$target', which doesn't exist")
        }
        if (cfg.mode == BedrockMenu.Mode.MODAL) {
            if (cfg.confirm == null) add("bedrock.mode is MODAL, which needs a confirm button")
            if (cfg.cancel == null) add("bedrock.mode is MODAL, which needs a cancel button")
        }
        if (cfg.mode == BedrockMenu.Mode.CUSTOM && cfg.fields.isEmpty()) {
            add("bedrock.mode is CUSTOM, which has no fields")
        }
        val seenIds = HashSet<String>()
        for (field in cfg.fields) {
            val id = when (field) {
                is BedrockField.Input -> field.id
                is BedrockField.Toggle -> field.id
                is BedrockField.Dropdown -> field.id
                is BedrockField.Slider -> field.id
                is BedrockField.StepSlider -> field.id
                is BedrockField.Label -> null
            }
            if (id != null && !seenIds.add(id)) add("bedrock.fields has duplicate id '$id'")
        }
    }

    /** Every action reachable in [definition], walking nested actions (`delay`, `chance`, `all`, `random`, `run-for`, `input`) and gates' `deny` lists. */
    private fun allActionsOf(definition: Menu): List<Action> = buildList {
        fun visit(action: Action) {
            add(action)
            when (action) {
                is Actions.Delay -> visit(action.action)
                is Actions.Chance -> visit(action.action)
                is Flow.All -> action.actions.forEach(::visit)
                is Flow.Pick -> action.actions.forEach(::visit)
                is Flow.RunFor -> action.actions.forEach(::visit)
                is Social.Input -> {
                    action.then.forEach(::visit)
                    action.onCancel.forEach(::visit)
                }
            }
        }
        fun visitGate(gate: Gate?) {
            if (gate != null) {
                gate.deny.forEach(::visit)
                gate.require.forEach { it.deny.forEach(::visit) }
            }
        }
        fun visitItem(item: Item) {
            visitGate(item.view)
            val clicks = item.click
            listOfNotNull(clicks.any, clicks.left, clicks.right, clicks.shiftLeft, clicks.shiftRight, clicks.middle).forEach { handler ->
                visitGate(handler.require)
                handler.actions.forEach(::visit)
            }
            item.accept?.let { accept ->
                accept.then.forEach(::visit)
                accept.reject.forEach(::visit)
            }
        }
        visitGate(definition.open)
        definition.onOpen.forEach(::visit)
        definition.onClose.forEach(::visit)
        definition.openWith.forEach { visitGate(it.require) }
        definition.items.values.forEach(::visitItem)
        definition.pages?.let { pages ->
            pages.item?.let(::visitItem)
            pages.matrix?.templates?.values?.forEach(::visitItem)
            pages.empty?.let(::visitItem)
        }
        definition.animation?.let { anim ->
            anim.onStart.forEach(::visit)
            anim.onEnd.forEach(::visit)
            anim.frames.forEach { frame ->
                visitGate(frame.require)
                frame.onStart.forEach(::visit)
                frame.onEnd.forEach(::visit)
                frame.items.values.forEach(::visitItem)
            }
        }
        definition.bedrock?.let { cfg ->
            cfg.buttons.forEach { button ->
                visitGate(button.view)
                visitGate(button.require)
                button.actions.forEach(::visit)
            }
            cfg.confirm?.let { c -> visitGate(c.require); c.actions.forEach(::visit) }
            cfg.cancel?.let { c -> visitGate(c.require); c.actions.forEach(::visit) }
            cfg.onSubmit.forEach(::visit)
            cfg.onClose.forEach(::visit)
        }
    }

    /** What is wrong with [item] taking items from the player, in [definition]: nothing when it takes none. */
    private fun acceptProblems(definition: Menu, item: Item): List<String> = buildList {
        val accept = item.accept ?: return@buildList
        if (definition.items.values.any { it.inventorySlots.isNotEmpty() }) add("accepts items, which can't work in a menu that draws in the player's inventory")
        accept.material?.let { if (!hasPlaceholder(it) && ItemFactory.material(it) == null) add("accepts '$it', which isn't an item") }
    }

    /** What the plugin that added the activator [activator] makes of its keys, null if there is no such plugin or it refuses them. */
    private fun filterOf(activator: Activator.Addon): ActivatorFilter? = try {
        plugin.registry.activator(activator.name)?.create(Settings(ConfigFactory.parseString(activator.values, MenuFormat.JSON_PARSE).root().unwrapped()))
    } catch (e: RuntimeException) {
        null
    }

    /** What is wrong with [activator] that does not depend on who triggers it. */
    private fun activatorProblems(activator: Activator): List<String> = buildList {
        when (activator) {
            is Activator.HeldItem -> {
                if (activator.material == null && activator.name == null && activator.model == null) add("an item activator without material, name or model fires for every click with anything in hand")
                activator.material?.let { if (!hasPlaceholder(it) && ItemFactory.material(it) == null) add("material '$it' isn't a known item") }
            }
            is Activator.Block -> {
                if (activator.types.isEmpty() && activator.at == null) add("a block activator without types or at fires for every block")
                activator.types.filterNot { it.startsWith("#") }.filter { Material.matchMaterial(key(it))?.isBlock != true }.forEach { add("'$it' isn't a block") }
                activator.types.filter { it.startsWith("#") && NamespacedKey.fromString(key(it.removePrefix("#"))) == null }.forEach { add("'$it' isn't a tag") }
            }
            is Activator.Entity -> {
                if (activator.types.isEmpty() && activator.name == null) add("an entity activator without types or name fires for every entity")
                activator.types.filter { entityType(it) == null }.forEach { add("'$it' isn't an entity type") }
            }
            is Activator.Npc -> if (!Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")) add("an npc activator needs FancyNpcs, which isn't installed")
            is Activator.RegionEnter, is Activator.RegionLeave ->
                if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) add("a region activator needs WorldGuard, which isn't installed")
            is Activator.Chat -> if (activator.text.isEmpty()) add("a chat activator with empty text")
            is Activator.Addon -> {
                val type = plugin.registry.activator(activator.name)
                if (type == null) add("the activator '${activator.name}' isn't added by any plugin that is installed")
                else try {
                    type.create(Settings(ConfigFactory.parseString(activator.values, MenuFormat.JSON_PARSE).root().unwrapped()))
                } catch (e: RuntimeException) {
                    add("the activator '${activator.name}' refuses its keys: ${e.message}")
                }
            }
            is Activator.Join, is Activator.SwapHands -> Unit
        }
    }

    private fun entityType(spec: String) = NamespacedKey.fromString(key(spec))?.let { RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE).get(it) }

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

    private fun open(player: Player, target: LoadedMenu, args: List<String>, depth: Int = 0) {
        val menu = target.menu
        val named = MenuArgs.bind(menu.args, args)
        if (named == null) {
            player fail plugin.messageConfig.data.commands.menu.usage(menu.commands.firstOrNull() ?: target.name, menu.argsUsage ?: MenuArgs.usage(menu.args))
            return
        }
        val ctx = Context(player, target.name, named, plugin.actions)

        if (!menu.open.passes(ctx, run = true)) return

        val profile = Clients.profile(player)
        when (val presentation = BedrockPlanner.plan(menu, profile, plugin.config.data.bedrock)) {
            is Presentation.Redirect -> {
                if (depth < 4) {
                    val redirected = menu(presentation.menu)
                    if (redirected != null) {
                        open(player, redirected, args, depth + 1)
                        return
                    }
                    plugin.logger.warning("Menu '${target.name}' redirects Bedrock players to '${presentation.menu}', which doesn't exist")
                } else {
                    plugin.logger.warning("Menu '${target.name}' exceeded the Bedrock redirect limit")
                }
            }
            Presentation.SimpleForm, Presentation.ModalForm, Presentation.CustomForm -> {
                if (plugin.bedrockForms.available) {
                    val hadInventory = views.remove(player.uniqueId)
                    hadInventory?.stopTasks()
                    plugin.screens.end(player, resync = hadInventory != null)
                    if (hadInventory != null) player.closeInventory()
                    if (plugin.bedrockForms.open(player, target, ctx, presentation)) return
                }
            }
            Presentation.Inventory -> Unit
        }

        plugin.bedrockForms.left(player)
        val view = MenuView(plugin, target.name, menu, ctx, target.bySlot, target.inventoryBySlot, profile)
        val title = view.openTitle()
        val inventory = if (menu.type == "chest") {
            val rows = menu.size.coerceIn(9, 54) / 9 * 9
            Bukkit.createInventory(view, rows, title)
        } else {
            val type = runCatching { InventoryType.valueOf(menu.type.uppercase()) }.getOrDefault(InventoryType.CHEST)
            Bukkit.createInventory(view, type, title)
        }
        view.attach(inventory)
        val drawOverlay = target.inventoryBySlot.isNotEmpty() && BedrockPlanner.shouldDrawInventoryOverlay(menu, profile, plugin.config.data.bedrock)
        if (!drawOverlay) plugin.screens.end(player, resync = false)
        else if (!plugin.screens.available) plugin.screens.unavailable(target.name)
        else plugin.screens.expect(player, inventory.size)
        view.draw()

        // Registered first, so closing the menu this player had open is not taken for the end of this one.
        views.put(player.uniqueId, view)?.stopTasks()
        if (player.openInventory(inventory) == null) {
            // Another plugin canceled it.
            views.remove(player.uniqueId, view)
            plugin.screens.end(player, resync = false)
            return
        }
        menu.refresh?.let { view.startRefresh(ticks(it)) }
        view.startAnimation()
        debug("{0} opened {1} (protocol={2})", player.name, target.name, Clients.versionOf(player) ?: "native")
        plugin.actions.run(menu.onOpen, view.ctx)
    }

    /** The player closed [view], rather than it being replaced by another menu. */
    fun closed(player: Player, view: MenuView) {
        if (!views.remove(player.uniqueId, view)) return
        view.stopTasks()
        plugin.screens.end(player)
        plugin.actions.run(view.menu.onClose, view.ctx)
    }

    /** The player left; there is nobody to run the close actions for. */
    fun left(player: Player) {
        views.remove(player.uniqueId)?.stopTasks()
        plugin.bedrockForms.left(player)
        plugin.screens.end(player, resync = false)
    }

    /** Closes every open menu, for when the plugin goes away. */
    fun closeAll() {
        for (id in views.keys.toList()) {
            val view = views.remove(id) ?: continue
            view.stopTasks()
            runCatching { view.player.closeInventory() }
            runCatching { plugin.screens.end(view.player) }
        }
        plugin.bedrockForms.closeAll()
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
