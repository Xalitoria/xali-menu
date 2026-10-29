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
internal fun ConfigValue.packExternals(names: Set<String>): ConfigValue = when (this) {
    is ConfigObject -> {
        val type = this["type"]?.takeIf { it.valueType() == ConfigValueType.STRING }?.unwrapped() as? String
        if (type != null && type in names) ConfigValueFactory.fromMap(
            mapOf("type" to type, "values" to withoutKey("type").render(ConfigRenderOptions.concise())),
        )
        else ConfigValueFactory.fromMap(mapValues { it.value.packExternals(names) })
    }
    is com.typesafe.config.ConfigList -> ConfigValueFactory.fromIterable(map { it.packExternals(names) })
    else -> this
}
