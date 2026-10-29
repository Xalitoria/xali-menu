package mc.xalitoria.menu.paper.format

import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigUtil
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueType.LIST
import com.typesafe.config.ConfigValueType.NULL
import com.typesafe.config.ConfigValueType.OBJECT
import com.typesafe.config.ConfigValueType.STRING

/**
 * Writes a parsed config as HOCON a person can edit. The library's own renderer puts every value of every object
 * on a line of its own and sorts the keys, which turns a click action into seven lines; this keeps an object or
 * list on one line while it is short and plain, and puts the keys that name things (`type`, `material`) first.
 */
internal object HoconWriter {
    private const val WIDTH = 100
    private const val INDENT = "    "

    /** Keys written ahead of the others, in this order. */
    private val FIRST = ["type", "title", "size", "material", "slot", "slots", "name", "command"]

    fun write(root: ConfigObject): String = body(root, "")

    private fun body(obj: ConfigObject, indent: String): String = keys(obj).joinToString("") { key ->
        val value = obj.getValue(key)
        if (value.valueType() == OBJECT && inline(value) == null) {
            "$indent${label(key)} {\n${body(value as ConfigObject, indent + INDENT)}$indent}\n"
        } else {
            "$indent${label(key)} = ${value(value, indent)}\n"
        }
    }

    private fun value(value: ConfigValue, indent: String): String = inline(value) ?: when (value.valueType()) {
        LIST -> (value as Iterable<*>).joinToString("", "[\n", "$indent]") {
            "${indent + INDENT}${value(it as ConfigValue, indent + INDENT)}\n"
        }
        else -> "{\n${body(value as ConfigObject, indent + INDENT)}$indent}"
    }

    /** [value] on one line, or null if it has to span several. */
    private fun inline(value: ConfigValue): String? {
        val text = when (value.valueType()) {
            STRING -> ConfigUtil.quoteString(value.unwrapped() as String)
            NULL -> "null"
            OBJECT -> {
                val obj = value as ConfigObject
                val entries = keys(obj).map { key -> inline(obj.getValue(key))?.let { "${label(key)} = $it" } }
                if (null in entries) return null
                if (entries.isEmpty()) "{}" else entries.joinToString(", ", "{ ", " }")
            }
            LIST -> {
                val items = (value as Iterable<*>).map { inline(it as ConfigValue) }
                if (null in items) return null
                items.joinToString(", ", "[", "]")
            }
            else -> value.unwrapped().toString()
        }
        return text.takeIf { value.valueType() !in [OBJECT, LIST] || it.length <= WIDTH }
    }

    private fun label(key: String): String = if (key.matches(BARE)) key else ConfigUtil.quoteString(key)

    private fun keys(obj: ConfigObject): List<String> =
        obj.keys.sortedWith(compareBy({ FIRST.indexOf(it).let { i -> if (i < 0) FIRST.size else i } }, { it }))

    private val BARE = Regex("[A-Za-z_][A-Za-z0-9_-]*")
}
