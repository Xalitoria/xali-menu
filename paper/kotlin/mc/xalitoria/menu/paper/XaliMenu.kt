package mc.xalitoria.menu.paper

import com.xalitoria.util.bukkit.coroutineScope
import com.xalitoria.util.config.YamlFile
import com.xalitoria.util.message.*
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.api.ApiImpl
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.catalog.Catalogs
import mc.xalitoria.menu.paper.command.MenuCommand
import mc.xalitoria.menu.paper.command.OpenCommands
import mc.xalitoria.menu.paper.command.Permissions
import mc.xalitoria.menu.paper.command.VariableCommand
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.paper.config.MainConfig
import mc.xalitoria.menu.paper.config.MessageConfig
import mc.xalitoria.menu.paper.editor.EditorRelay
import mc.xalitoria.menu.paper.editor.EditorServer
import mc.xalitoria.menu.paper.editor.EditorSessions
import mc.xalitoria.menu.paper.bedrock.BedrockForms
import mc.xalitoria.menu.paper.bedrock.ClientProfile
import mc.xalitoria.menu.paper.hook.BedrockHooks
import mc.xalitoria.menu.paper.hook.Economy
import mc.xalitoria.menu.paper.hook.FancyNpcs
import mc.xalitoria.menu.paper.hook.HeadDatabaseSource
import mc.xalitoria.menu.paper.hook.Heads
import mc.xalitoria.menu.paper.hook.ItemsAdderSource
import mc.xalitoria.menu.paper.hook.NexoSource
import mc.xalitoria.menu.paper.hook.Regions
import mc.xalitoria.menu.paper.hook.Skins
import mc.xalitoria.menu.paper.runtime.ActionRunner
import mc.xalitoria.menu.paper.runtime.ActivatorListener
import mc.xalitoria.menu.paper.runtime.Anvils
import mc.xalitoria.menu.paper.runtime.Clients
import mc.xalitoria.menu.paper.runtime.Deposits
import mc.xalitoria.menu.paper.runtime.Dialogs
import mc.xalitoria.menu.paper.runtime.Inputs
import mc.xalitoria.menu.paper.runtime.ItemFactory
import mc.xalitoria.menu.paper.runtime.LoadedMenu
import mc.xalitoria.menu.paper.runtime.MenuListener
import mc.xalitoria.menu.paper.runtime.MenuManager
import mc.xalitoria.menu.paper.runtime.PluginListener
import mc.xalitoria.menu.paper.screen.Screens
import mc.xalitoria.menu.paper.variable.VariableExpansion
import mc.xalitoria.menu.paper.variable.VariableStore
import net.xalitoria.menu.XaliMenuApi
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class XaliMenu : JavaPlugin() {
    lateinit var config: YamlFile<MainConfig>
        private set

    lateinit var messageConfig: YamlFile<MessageConfig>
        private set

    lateinit var scope: CoroutineScope
        private set

    private lateinit var economyHook: Economy

    /** The actions, conditions and item sources there are, the built-in ones and those other plugins added. */
    lateinit var registry: TypeRegistry
        private set

    lateinit var heads: Heads
        private set

    /** The skins of players, when SkinsRestorer is here. */
    var skins: Skins? = null
        private set

    /** The regions of WorldGuard, when it is here. */
    var regions: Regions? = null
        private set

    lateinit var items: ItemFactory
        private set

    lateinit var inputs: Inputs
        private set

    lateinit var dialogs: Dialogs
        private set

    lateinit var deposits: Deposits
        private set

    lateinit var anvils: Anvils
        private set

    lateinit var actions: ActionRunner
        private set

    lateinit var menus: MenuManager
        private set

    /** The endpoint of the web editor; listening only when `editor.enabled` is on. */
    internal lateinit var editor: EditorServer

    /** The shared links of the editor, through a relay. */
    internal lateinit var relay: EditorRelay
        private set

    /** Hears what the players do that opens menus, and what other plugins tell it about. */
    lateinit var activators: ActivatorListener
        private set

    lateinit var variables: VariableStore
        private set

    /** Draws in the bottom rows of a menu's window, the player's inventory, through the packets that fill it. */
    lateinit var screens: Screens
        private set

    /** Bedrock Edition detection and native Cumulus forms, through Floodgate or Geyser-Spigot. */
    lateinit var bedrockForms: BedrockForms
        private set

    private var expansion: VariableExpansion? = null

    override fun onEnable() {
        INSTANCE = this
        scope = coroutineScope()

        config = YamlFile(dataPath.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        config.load()
        // Written back so that an option added by an update shows up in the file.
        config.save()
        loadMessages()
        applyDebug()

        val pm = server.pluginManager
        // The server's own database is read here, which is quick. The shared one is connected to in the background, so a
        // database that is slow or down does not hold the server up; its variables read as unresolved until it has answered.
        variables = VariableStore.create(dataPath, config.data, scope, logger)

        economyHook = Economy(this)
        registry = TypeRegistry()
        Actions.register(registry)
        Conditions.register(registry)
        Catalogs.register(registry)
        // Each is only made when its plugin is here, since making one loads that plugin's classes.
        if (pm.isPluginEnabled("ItemsAdder")) {
            registry.addSource(null, ItemsAdderSource())
            registry.addSource(null, mc.xalitoria.menu.paper.hook.ItemsAdderAlias())
            mc.xalitoria.menu.paper.hook.ItemsAdderTags.enabled = true
            pm.registerEvents(mc.xalitoria.menu.paper.hook.ItemsAdderListener(this), this)
        }
        if (pm.isPluginEnabled("Nexo")) {
            registry.addSource(null, NexoSource())
            mc.xalitoria.menu.paper.hook.NexoTags.enabled = true
        }
        val hdb = if (pm.isPluginEnabled("HeadDatabase")) HeadDatabaseSource().also { registry.addSource(null, it) } else null
        heads = Heads(this, pm.isPluginEnabled("SkinsRestorer"), { hdb?.texture(it) })
        if (pm.isPluginEnabled("SkinsRestorer")) skins = Skins(this)
        if (pm.isPluginEnabled("WorldGuard")) regions = Regions()
        items = ItemFactory(this)
        actions = ActionRunner(this)
        screens = Screens(this).also { it.start() }
        bedrockForms = BedrockForms(this, BedrockHooks.detect(this))
        Clients.profileOf = { player -> bedrockForms.bridge?.profile(player) ?: ClientProfile.JAVA }
        menus = MenuManager(this)
        menus.watchVariables(variables.changes, scope)
        server.messenger.registerOutgoingPluginChannel(this, "BungeeCord")
        server.servicesManager.register(XaliMenuApi::class.java, ApiImpl(this), this, ServicePriority.Normal)

        saveExampleMenu()
        logger info "Item sources: ${registry.sources.keys.ifEmpty { listOf("none") }.joinToString()}; economy: ${if (economyHook.available) "yes" else "no"}; skins: ${if (pm.isPluginEnabled("SkinsRestorer")) "SkinsRestorer" else "Mojang"}; player inventory: ${screens.name ?: "off (needs PacketEvents or ProtocolLib)"}; bedrock: ${bedrockForms.name ?: "off (needs Floodgate or Geyser)"}"
        expansion = VariableExpansion(this).also { it.register() }

        // The commands are registered when the server starts, so the labels are read now; the menus themselves are read
        // once every plugin has enabled (see PluginListener), with a fallback for a plugin enabled after startup.
        menus.scan()
        val listener = PluginListener(this)
        pm.registerEvents(listener, this)
        pm.registerEvents(MenuListener(this), this)
        activators = ActivatorListener(this)
        pm.registerEvents(activators, this)
        if (pm.isPluginEnabled("FancyNpcs")) FancyNpcs(this, activators).register()
        inputs = Inputs(this)
        pm.registerEvents(inputs, this)
        deposits = Deposits(this)
        dialogs = Dialogs(this).also { pm.registerEvents(it, this) }
        anvils = Anvils(this).also { pm.registerEvents(it, this) }
        // A paper of an anvil left in somebody's hands by a window that was cut off before this start (a reload) is taken away,
        // and any players already online have their textured profiles cached for heads without hitting Mojang.
        server.onlinePlayers.forEach { player ->
            player.scheduler.run(this, {
                anvils.sweep(player)
                heads.remember(player)
            }, null)
        }
        editor = EditorServer(this, EditorSessions({ config.data.editor.sessionMinutes }))
        relay = EditorRelay(this, editor.api)
        editor.api.onHeld = { edit ->
            val message = messageConfig.data.commands.edit.heldNotice(edit.from, edit.name, edit.id)
            server.onlinePlayers.filter { it.hasPermission(Permissions.ADMIN) }.forEach { it info message }
            logger.info("${edit.from} sent an edit of '${edit.name}' (#${edit.id}); /xmenu edits lists it")
        }
        editor.api.onSaved = { owner, name, reloaded, problems ->
            val editMsg = messageConfig.data.commands.edit
            val text = if (reloaded) editMsg.savedReloadedNotice(name.verbatim()) else editMsg.savedNotice(name.verbatim())
            server.getPlayerExact(owner)?.let { player ->
                player.scheduler.run(this, {
                    player pass text
                    if (problems.isNotEmpty()) {
                        player warn messageConfig.data.commands.tools.problems(name.verbatim(), problems.size)
                        problems.forEach { player info it.verbatim() }
                    }
                }, null)
            }
        }
        startEditor()
        server.globalRegionScheduler.runDelayed(this, { if (!menus.isLoaded) listener.loadMenus() }, 40)

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            MenuCommand.register(event.registrar())
            OpenCommands.register(event.registrar())
            VariableCommand.register(event.registrar())
        }
        debug("Enabled: language={0}", config.data.language)
    }

    override fun onDisable() {
        if (::menus.isInitialized) menus.closeAll()
        // The questions that are open (a dialog, an anvil with its paper) are taken away rather than left to the server's shutdown.
        if (::inputs.isInitialized) inputs.stop()
        if (::screens.isInitialized) screens.stop()
        if (::relay.isInitialized) relay.stop()
        if (::editor.isInitialized) editor.stop()
        expansion?.unregister()
        server.servicesManager.unregisterAll(this)
        server.messenger.unregisterOutgoingPluginChannel(this)
        if (::variables.isInitialized) runBlocking { variables.close() }
        scope.cancel("XaliMenu is disabling")
    }

    /** Reads the configuration and the messages file it names again, writing back any option that was missing, and the menus. */
    fun reload() {
        config.load()
        config.save()
        loadMessages()
        applyDebug()
        heads.clear()
        server.onlinePlayers.forEach { player -> player.scheduler.run(this, { heads.remember(player) }, null) }
        startEditor()
        val before = OpenCommands.registered
        logger pass messageConfig.data.commands.menu.loaded(menus.load())
        val changed = menus.registered().keys.symmetricDifference(before)
        if (changed.isNotEmpty()) logger warn messageConfig.data.commands.menu.commandsChanged(changed.joinToString { "/$it" })
        debug("Reloaded: language={0}", config.data.language)
        logger.pass(messageConfig.data.commands.reload.pass)
    }

    /** Starts or stops the editor endpoint to match `editor.enabled`; a changed address or port takes effect here too. */
    private fun startEditor() {
        editor.stop()
        if (!config.data.editor.enabled) return
        val settings = config.data.editor
        val failure = editor.start()
        if (failure != null) logger warn messageConfig.data.commands.edit.notListening("${settings.host}:${settings.port}", failure)
        else logger info "The menu editor listens on ${settings.host}:${settings.port}; /xmenu edit prints a link for it."
    }

    /** The first start has no menus, so two are written to show the format. */
    private fun saveExampleMenu() {
        val directory = dataPath.resolve("menus")
        if (directory.exists()) return
        directory.createDirectories()
        for (example in listOf("example.conf", "example-pages.conf")) {
            getResource(example)?.use { Files.copy(it, directory.resolve(example)) }
        }
    }

    /** The messages file is named by the configuration, so a new language means a new file, not a reload of the old. */
    private fun loadMessages() {
        messageConfig = YamlFile(
            dataPath.resolve("language", config.data.language + ".yml"),
            MessageConfig.serializer(),
            ::MessageConfig,
        )
        messageConfig.load()
        messageConfig.save()
    }

    private fun applyDebug() {
        debugger =
            if (config.data.debug) { message: String, args: Array<out Any?> -> logger.info(message.formatArgs(*args)) }
            else { message: String, args: Array<out Any?> -> }
    }

    private fun <T> Set<T>.symmetricDifference(other: Set<T>): Set<T> = (this - other) + (other - this)

    companion object {
        internal lateinit var INSTANCE: XaliMenu
        private var debugger = { message: String, args: Array<out Any?> -> }
        fun debug(message: String, vararg args: Any?) = debugger(message, args)

        /** The economy hook of the running plugin; null where there is no plugin, as in a unit test. */
        val economy: Economy? get() = if (::INSTANCE.isInitialized) INSTANCE.economyHook else null

        /** The menus the running plugin has loaded; none where there is no plugin, as in a unit test. */
        fun loadedMenus(): Collection<LoadedMenu> = if (::INSTANCE.isInitialized && INSTANCE::menus.isInitialized) INSTANCE.menus.menus else emptyList()
    }
}
