package mc.xalitoria.menu.paper.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.ActionType
import mc.xalitoria.menu.api.Condition
import mc.xalitoria.menu.api.ConditionType
import mc.xalitoria.menu.api.ItemSource
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
    private val itemSources = ConcurrentHashMap<String, Source>()

    @Volatile
    var module: SerializersModule = build()
        private set

    /** The names (`plugin:name`) of the actions and conditions other plugins added: the `type`s that need [packExternals][mc.xalitoria.menu.paper.format.packExternals]. */
    val externalNames: Set<String> get() = (externalActions + externalConditions).mapTo(HashSet()) { it.name }

    /** The item sources by prefix. */
    val sources: Map<String, ItemSource> get() = itemSources.mapValues { it.value.source }

    fun <T : Action> addAction(owner: Plugin?, type: KClass<T>, serializer: KSerializer<T>) {
        add(actions, Type(owner, type, serializer))
    }

    fun <T : Condition> addCondition(owner: Plugin?, type: KClass<T>, serializer: KSerializer<T>) {
        add(conditions, Type(owner, type, serializer))
    }

    /** Adds the action [name] of the plugin [owner]; [type] builds it from the keys written in the menu file. */
    fun addAction(owner: Plugin, name: String, type: ActionType) {
        addExternal(externalActions, owner, name, ExternalSerializer(name, type::create))
    }

    fun addCondition(owner: Plugin, name: String, type: ConditionType) {
        addExternal(externalConditions, owner, name, ExternalSerializer(name, type::create))
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

    fun addSource(owner: Plugin?, source: ItemSource) {
        val prefix = source.prefix.lowercase()
        require(itemSources.putIfAbsent(prefix, Source(owner, source)) == null) { "There is already an item source '$prefix'" }
    }

    /** Takes away everything [owner] added. @return whether there was anything */
    fun removeAll(owner: Plugin): Boolean {
        val removed = actions.removeIf { it.owner == owner } or conditions.removeIf { it.owner == owner } or
            externalActions.removeIf { it.owner == owner } or externalConditions.removeIf { it.owner == owner } or
            itemSources.values.removeIf { it.owner == owner }
        if (removed) module = build()
        return removed
    }

    @Suppress("UNCHECKED_CAST")
    private fun <B : Any> add(list: CopyOnWriteArrayList<Type<B>>, type: Type<out B>) {
        // Actions and conditions are read apart, so `permission` can be both.
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
    }

    private companion object {
        /** `plugin:name`, in lower case, such as a namespaced key. */
        val NAME = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}
