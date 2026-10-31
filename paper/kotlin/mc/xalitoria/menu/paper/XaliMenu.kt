package mc.xalitoria.menu.paper

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mc.xalitoria.menu.api.XaliMenuApi
import mc.xalitoria.menu.paper.action.Actions
import mc.xalitoria.menu.paper.api.ApiImpl
import mc.xalitoria.menu.paper.api.TypeRegistry
import mc.xalitoria.menu.paper.command.MenuCommand
import mc.xalitoria.menu.paper.command.OpenCommands
import mc.xalitoria.menu.paper.command.VariableCommand
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.paper.config.MainConfig
import mc.xalitoria.menu.paper.config.MessageConfig
import mc.xalitoria.menu.paper.hook.Economy
import mc.xalitoria.menu.paper.hook.HeadDatabaseSource
import mc.xalitoria.menu.paper.hook.Heads
import mc.xalitoria.menu.paper.hook.ItemsAdderSource
import mc.xalitoria.menu.paper.hook.NexoSource
import mc.xalitoria.menu.paper.runtime.ActionRunner
import mc.xalitoria.menu.paper.runtime.ItemFactory
import mc.xalitoria.menu.paper.runtime.MenuListener
import mc.xalitoria.menu.paper.runtime.MenuManager
import mc.xalitoria.menu.paper.runtime.PluginListener
import mc.xalitoria.menu.paper.variable.VariableExpansion
import mc.xalitoria.menu.paper.variable.VariableStore
import mc.xalitoria.util.config.YamlFile
import mc.xalitoria.util.message.*
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

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

    lateinit var items: ItemFactory
        private set

    lateinit var actions: ActionRunner
        private set

    lateinit var menus: MenuManager
        private set

    lateinit var variables: VariableStore
        private set

    private var expansion: VariableExpansion? = null

    override fun onEnable() {
        INSTANCE = this
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
                logger.fail("Unhandled coroutine failure", throwable)
            }
        )

        config = YamlFile(dataPath.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        config.load()
        // Written back so that an option added by an update shows up in the file.
        config.save()
        loadMessages()
        applyDebug()

        val pm = server.pluginManager
        // The server's own database is read here, which is quick. The shared one is connected to in the background, so a
        // database that is slow or down doesn't hold the server up; its variables read as unresolved until it has answered.
        variables = VariableStore.create(dataPath, config.data, scope, logger)

        economyHook = Economy(this)
        registry = TypeRegistry()
        Actions.register(registry)
        Conditions.register(registry)
        // Each is only made when its plugin is here, since making one loads that plugin's classes.
        if (pm.isPluginEnabled("ItemsAdder")) registry.addSource(null, ItemsAdderSource())
        if (pm.isPluginEnabled("Nexo")) registry.addSource(null, NexoSource())
        if (pm.isPluginEnabled("HeadDatabase")) registry.addSource(null, HeadDatabaseSource())
        heads = Heads(this, pm.isPluginEnabled("SkinsRestorer"))
        items = ItemFactory(this)
        actions = ActionRunner(this)
        menus = MenuManager(this)
        server.messenger.registerOutgoingPluginChannel(this, "BungeeCord")
        server.servicesManager.register(XaliMenuApi::class.java, ApiImpl(this), this, ServicePriority.Normal)

        saveExampleMenu()
        logger info "Item sources: ${registry.sources.keys.ifEmpty { listOf("none") }.joinToString()}; economy: ${if (economyHook.available) "yes" else "no"}; skins: ${if (pm.isPluginEnabled("SkinsRestorer")) "SkinsRestorer" else "Mojang"}"
        expansion = VariableExpansion(this).also { it.register() }

        // The commands are registered when the server starts, so the labels are read now; the menus themselves are read
        // once every plugin has enabled (see PluginListener), with a fallback for a plugin enabled after startup.
        menus.scan()
        val listener = PluginListener(this)
        pm.registerEvents(listener, this)
        pm.registerEvents(MenuListener(this), this)
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
        val before = OpenCommands.registered
        logger pass messageConfig.data.commands.menu.loaded(menus.load())
        val changed = menus.registered().keys.symmetricDifference(before)
        if (changed.isNotEmpty()) logger warn messageConfig.data.commands.menu.commandsChanged(changed.joinToString { "/$it" })
        debug("Reloaded: language={0}", config.data.language)
        logger.pass(messageConfig.data.commands.reload.pass)
    }

    /** The first start has no menus, so one is written to show the format. */
    private fun saveExampleMenu() {
        val directory = dataPath.resolve("menus")
        if (directory.exists()) return
        directory.createDirectories()
        getResource("example.conf")?.use { Files.copy(it, directory.resolve("example.conf")) }
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
    }
}
