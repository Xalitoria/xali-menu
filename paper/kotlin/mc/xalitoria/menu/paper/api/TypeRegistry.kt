package mc.xalitoria.menu.paper.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import net.xalitoria.menu.Action
import net.xalitoria.menu.ActivatorType
import net.xalitoria.menu.Catalog
import net.xalitoria.menu.CatalogType
import net.xalitoria.menu.ActionType
import net.xalitoria.menu.Condition
import net.xalitoria.menu.ConditionType
import net.xalitoria.menu.ItemSource
import org.bukkit.plugin.Plugin

/**
 * The actions, conditions and item sources there are: the built-in ones and what other plugins added. The first two are
 * what menu files are read with, so their [module] is rebuilt whenever one comes or goes. A null owner is XaliMenu itself.
 */
class TypeRegistry {
    private class Type<B : Any>(val owner: Plugin?, val type: KClass<out B>, val serializer: KSerializer<out B>) {
        val name: String get() = serializer.descriptor.serialName
    }

    private class Source(val owner: Plugin?, val source: ItemSource)

    /** An action or condition another plugin added, read through [ExternalSerializer]. */
    private class External<B : Any>(val owner: Plugin, val name: String, val serializer: DeserializationStrategy<B>)

    private val actions = CopyOnWriteArrayList<Type<Action>>()
    private val conditions = CopyOnWriteArrayList<Type<Condition>>()
    private val externalActions = CopyOnWriteArrayList<External<Action>>()
    private val externalConditions = CopyOnWriteArrayList<External<Condition>>()
    private val catalogs = CopyOnWriteArrayList<Type<Catalog>>()
    private val externalCatalogs = CopyOnWriteArrayList<External<Catalog>>()
    private class ExternalActivator(val owner: Plugin, val type: ActivatorType)

    /** What the editor is told about a catalog: what its elements hold, and (for another plugin's) which keys its settings take, each with an example. */
    class CatalogInfo(val owner: Plugin?, val values: Map<String, String>, val settings: Map<String, String>)

    private val catalogValues = ConcurrentHashMap<String, CatalogInfo>()
    private val activators = ConcurrentHashMap<String, ExternalActivator>()
    private val itemSources = ConcurrentHashMap<String, Source>()

    @Volatile
    var module: SerializersModule = build()
        private set

    /** The names (`plugin:name`) of the actions, conditions and catalogs other plugins added: the `type`s that need [packExternals][mc.xalitoria.menu.paper.format.packExternals]. */
    val externalNames: Set<String> get() = (externalActions + externalConditions + externalCatalogs).mapTo(HashSet()) { it.name }

    /** What the elements of each catalog hold, as the name of each value with an example: for the editor. Built-in and other plugins' catalogs, by `type`. */
    val catalogInfo: Map<String, Map<String, String>> get() = catalogValues.mapValues { it.value.values }

    /** The keys the settings of each catalog take, with an example of each, where the plugin that added it said: for the editor, by `type`. */
    val catalogSettings: Map<String, Map<String, String>> get() = catalogValues.filterValues { it.settings.isNotEmpty() }.mapValues { it.value.settings }

    /** The names of the activators other plugins added, which a menu file writes as `type` and which [packExternals][mc.xalitoria.menu.paper.format.packExternals] turns into `addon` activators. */
    val activatorNames: Set<String> get() = activators.keys.toSet()

    /** The type of the activator [name], null if no plugin added one. */
    fun activator(name: String): ActivatorType? = activators[name]?.type

    /** The item sources by prefix. */
    val sources: Map<String, ItemSource> get() = itemSources.mapValues { it.value.source }

    fun <T : Action> addAction(owner: Plugin?, type: KClass<T>, serializer: KSerializer<T>) {
        add(actions, Type(owner, type, serializer))
    }

    fun <T : Condition> addCondition(owner: Plugin?, type: KClass<T>, serializer: KSerializer<T>) {
        add(conditions, Type(owner, type, serializer))
    }

    fun <T : Catalog> addCatalog(owner: Plugin?, type: KClass<T>, serializer: KSerializer<T>, values: Map<String, String> = emptyMap()) {
        val added = Type(owner, type, serializer)
        add(catalogs, added)
        catalogValues[added.name] = CatalogInfo(owner, values, emptyMap())
    }

    /** Adds the action [name] of the plugin [owner]; [type] builds it from the keys written in the menu file. */
    fun addAction(owner: Plugin, name: String, type: ActionType) {
        addExternal(externalActions, owner, name, ExternalSerializer(name, type::create))
    }

    fun addCondition(owner: Plugin, name: String, type: ConditionType) {
        addExternal(externalConditions, owner, name, ExternalSerializer(name, type::create))
    }

    /** Adds the catalog [name] of the plugin [owner]; [values] and [settings] are what the editor is told about it, see [net.xalitoria.menu.XaliMenuApi.registerCatalog]. */
    fun addCatalog(owner: Plugin, name: String, type: CatalogType, values: Map<String, String> = emptyMap(), settings: Map<String, String> = emptyMap()) {
        addExternal(externalCatalogs, owner, name, ExternalSerializer(name, type::create))
        catalogValues[name] = CatalogInfo(owner, values, settings)
    }

    fun addActivator(owner: Plugin, name: String, type: ActivatorType) {
        require(NAME.matches(name)) { "'$name' isn't a name for an activator; use lower case as plugin:name, such as myplugin:emote" }
        require(activators.putIfAbsent(name, ExternalActivator(owner, type)) == null) { "There is already an activator '$name'" }
    }

    private fun <B : Any> addExternal(list: CopyOnWriteArrayList<External<B>>, owner: Plugin, name: String, serializer: DeserializationStrategy<B>) {
        require(NAME.matches(name)) { "'$name' isn't a name for an action or condition; use lower case as plugin:name, such as myplugin:reward" }
        require(list.none { it.name == name }) { "There is already a '$name' of that kind" }
        list += External(owner, name, serializer)
        module = build()
    }

    /** Adds the built-in action [T]; an `object` works too. */
    inline fun <reified T : Action> action() = addAction(null, T::class, serializer<T>())

    inline fun <reified T : Condition> condition() = addCondition(null, T::class, serializer<T>())

    inline fun <reified T : Catalog> catalog(values: Map<String, String> = emptyMap()) = addCatalog(null, T::class, serializer<T>(), values)
    inline fun <reified T : Catalog> catalog(vararg values: Pair<String, String>) = addCatalog(null, T::class, serializer<T>(), values.toMap())

    fun addSource(owner: Plugin?, source: ItemSource) {
        val prefix = source.prefix.lowercase()
        require(itemSources.putIfAbsent(prefix, Source(owner, source)) == null) { "There is already an item source '$prefix'" }
    }

    /** Takes away everything [owner] added. @return whether there was anything */
    fun removeAll(owner: Plugin): Boolean {
        val removed =
            actions.removeIf { it.owner == owner } or
            conditions.removeIf { it.owner == owner } or
            catalogs.removeIf { it.owner == owner } or
            externalActions.removeIf { it.owner == owner } or
            externalConditions.removeIf { it.owner == owner } or
            externalCatalogs.removeIf { it.owner == owner } or
            activators.values.removeIf { it.owner == owner } or
            itemSources.values.removeIf { it.owner == owner } or
            catalogValues.values.removeIf { it.owner == owner }
        if (removed) module = build()
        return removed
    }

    @Suppress("UNCHECKED_CAST")
    private fun <B : Any> add(list: CopyOnWriteArrayList<Type<B>>, type: Type<out B>) {
        // Actions, conditions and catalogs are read apart, so `permission` can be both.
        require(list.none { it.name == type.name }) { "There is already a '${type.name}' of that kind" }
        list += type as Type<B>
        module = build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun build(): SerializersModule = SerializersModule {
        polymorphic(Action::class) {
            for (entry in actions) subclass(entry.type as KClass<Action>, entry.serializer as KSerializer<Action>)
            defaultDeserializer { name -> externalActions.firstOrNull { it.name == name }?.serializer }
        }
        polymorphic(Condition::class) {
            for (entry in conditions) subclass(entry.type as KClass<Condition>, entry.serializer as KSerializer<Condition>)
            defaultDeserializer { name -> externalConditions.firstOrNull { it.name == name }?.serializer }
        }
        polymorphic(Catalog::class) {
            for (entry in catalogs) subclass(entry.type as KClass<Catalog>, entry.serializer as KSerializer<Catalog>)
            defaultDeserializer { name -> externalCatalogs.firstOrNull { it.name == name }?.serializer }
        }
    }

    private companion object {
        /** `plugin:name`, in lower case, such as a namespaced key. */
        val NAME = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}
