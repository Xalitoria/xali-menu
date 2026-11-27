package mc.xalitoria.menu.paper.format

import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueFactory
import com.typesafe.config.ConfigValueType

/**
 * Rewrites every object whose `type` is one of [names] (the actions and conditions other plugins added) to
 * `{ type = <name>, values = "<its other keys as JSON>" }`. The plugin that added it has no serializer for it, only a
 * function from its keys to the object; this is how the keys reach that function through the one that reads the menu.
 */
internal fun ConfigValue.packExternals(names: Set<String>, activators: Set<String> = emptySet()): ConfigValue = when (this) {
    is ConfigObject -> {
        val type = this["type"]?.takeIf { it.valueType() == ConfigValueType.STRING }?.unwrapped() as? String
        when {
            type != null && type in names -> ConfigValueFactory.fromMap(
                mapOf("type" to type, "values" to withoutKey("type").render(ConfigRenderOptions.concise())),
            )
            // The keys every activator has stay where they are, and are read as they are for the others; the rest is the plugin's.
            type != null && type in activators -> {
                val own = mapValues { it.value.packExternals(names, activators) }.filterKeys { it in ACTIVATOR_KEYS }
                var rest: ConfigObject = this
                for (key in ACTIVATOR_KEYS) rest = rest.withoutKey(key)
                ConfigValueFactory.fromMap(own + mapOf("type" to "addon", "name" to type, "values" to rest.withoutKey("type").render(ConfigRenderOptions.concise())))
            }
            else -> ConfigValueFactory.fromMap(mapValues { entry ->
                // Packed item properties already carry { type, values }; leave them alone even if an action shares the same name.
                if (entry.key == "properties" && entry.value is ConfigObject && "material" in this) entry.value
                else entry.value.packExternals(names, activators)
            })
        }
    }
    is com.typesafe.config.ConfigList -> ConfigValueFactory.fromIterable(map { it.packExternals(names, activators) })
    else -> this
}

private val ACTIVATOR_KEYS = setOf("require", "cancel", "args")
