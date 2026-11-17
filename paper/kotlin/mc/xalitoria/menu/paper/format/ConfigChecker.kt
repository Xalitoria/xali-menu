package mc.xalitoria.menu.paper.format

import com.typesafe.config.ConfigList
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueFactory
import com.typesafe.config.ConfigValueType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.descriptors.getPolymorphicDescriptors
import kotlinx.serialization.modules.SerializersModule
import net.xalitoria.menu.model.Menu
import net.xalitoria.menu.model.Within
import kotlin.time.Duration

/** A thing found in a menu file: where ([path] and, when the file has it, [line]), and what. */
data class ConfigProblem(val path: String, val line: Int?, val message: String) {
    override fun toString() = buildString {
        append(if (path.isEmpty()) "the menu" else path)
        if (line != null && line > 0) append(" (line ").append(line).append(')')
        append(": ").append(message)
    }
}

/** What [ConfigChecker.check] makes of a file: the file with its easy spellings written the one way the model reads, and what is wrong or doubtful in it. */
class Checked(val config: ConfigObject, val errors: List<ConfigProblem>, val warnings: List<ConfigProblem>)

/** A menu file that the model cannot read; [problems] is every reason, with where it is. */
class MenuFormatException(val problems: List<ConfigProblem>) : RuntimeException(
    if (problems.size == 1) problems.single().toString() else "${problems.size} problems:" + problems.joinToString("") { "\n  - $it" },
)

/**
 * Walks a menu file along the descriptors of the model, which is what reads it, so it can say what the reader would only fail on, with the path to it: a missing
 * key, a value of the wrong kind, a word that is not one of the enum's or of the types of an action, and (as warnings, since the file still loads) a key that
 * nothing reads, with the key it is probably meant to be. It also writes the file's easy forms the one way the reader takes them: a single value where a list is
 * expected becomes a list of one (`slots = 5`), and an enum in any case becomes the upper case the model has (`rarity = epic`).
 *
 * The actions and conditions other plugins added ([externals], and the [activators] of other plugins) have no descriptor here, so what is inside them is left to
 * the plugin that added them.
 */
@OptIn(ExperimentalSerializationApi::class)
class ConfigChecker(
    private val module: SerializersModule,
    private val externals: Set<String> = emptySet(),
    private val activators: Set<String> = emptySet(),
) {
    private val errors = mutableListOf<ConfigProblem>()
    private val warnings = mutableListOf<ConfigProblem>()

    fun check(root: ConfigObject): Checked {
        errors.clear()
        warnings.clear()
        val normalized = walk(Menu.serializer().descriptor, root, "")
        val config = (normalized as? Map<*, *>)?.let { ConfigValueFactory.fromMap(it.mapKeys { entry -> entry.key.toString() }) } ?: root
        return Checked(config, errors.toList(), warnings.toList())
    }

    private fun error(path: String, value: ConfigValue?, message: String) { errors += ConfigProblem(path, value?.origin()?.lineNumber(), message) }

    private fun warn(path: String, value: ConfigValue?, message: String) { warnings += ConfigProblem(path, value?.origin()?.lineNumber(), message) }

    /** [value] as the model reads it: plain maps, lists and scalars, with the easy forms written out. */
    private fun walk(descriptor: SerialDescriptor, value: ConfigValue, path: String, within: Within? = null): Any? {
        if (value.valueType() == ConfigValueType.NULL) {
            if (!descriptor.isNullable) error(path, value, "is empty")
            return null
        }
        return when {
            descriptor.serialName.contains("Duration") -> scalar(value, path, "a time such as 5s, 500ms or 1m") { text -> runCatching { Duration.parse(text) }.isSuccess }
            descriptor.serialName.contains("DynamicInt") -> scalar(value, path, "a number or text with placeholders") { true }.also { range(value, path, within) }
            descriptor.serialName.contains("DynamicBoolean") -> scalar(value, path, "a switch or text with placeholders") { true }
            descriptor.kind is PrimitiveKind -> primitive(descriptor, value, path).also { range(value, path, within) }
            descriptor.kind == SerialKind.ENUM -> enumValue(descriptor, value, path)
            descriptor.kind == StructureKind.LIST -> list(descriptor, value, path, within)
            descriptor.kind == StructureKind.MAP -> map(descriptor, value, path)
            descriptor.kind == PolymorphicKind.OPEN || descriptor.kind == PolymorphicKind.SEALED -> polymorphic(descriptor, value, path)
            descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT -> structure(descriptor, value, path, skip = null)
            else -> value.unwrapped()
        }
    }

    private fun scalar(value: ConfigValue, path: String, expected: String, accepts: (String) -> Boolean): Any? {
        if (value.valueType() == ConfigValueType.OBJECT || value.valueType() == ConfigValueType.LIST) {
            error(path, value, "should be $expected")
            return null
        }
        val text = value.unwrapped().toString()
        if (!accepts(text)) error(path, value, "should be $expected, not '$text'")
        return value.unwrapped()
    }

    private fun primitive(descriptor: SerialDescriptor, value: ConfigValue, path: String): Any? = when (descriptor.kind) {
        PrimitiveKind.BOOLEAN -> scalar(value, path, "true or false") { it == "true" || it == "false" }
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> scalar(value, path, "a whole number") { it.toLongOrNull() != null }
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> scalar(value, path, "a number") { it.toDoubleOrNull() != null }
        else -> scalar(value, path, "text") { true }
    }

    /** A number written in the file that is outside what the model takes for it (see [Within]). Text with placeholders is only known when the item is drawn. */
    private fun range(value: ConfigValue, path: String, within: Within?) {
        if (within == null) return
        val number = value.takeIf { it.valueType() == ConfigValueType.NUMBER || it.valueType() == ConfigValueType.STRING }?.unwrapped()?.toString()?.trim()?.toDoubleOrNull() ?: return
        if (number >= within.min && number <= within.max) return
        val text = when {
            within.min != Long.MIN_VALUE && within.max != Long.MAX_VALUE -> "should be from ${within.min} to ${within.max}"
            within.min != Long.MIN_VALUE -> "can't be below ${within.min}"
            else -> "can't be above ${within.max}"
        }
        val shown = value.unwrapped().toString()
        if (within.strict) error(path, value, "$text, not $shown") else warn(path, value, "$text, not $shown")
    }

    private fun enumValue(descriptor: SerialDescriptor, value: ConfigValue, path: String): Any? {
        val names = descriptor.elementNames.toList()
        val written = (scalar(value, path, "one of ${names.joinToString(", ")}") { true } ?: return null).toString()
        if (written in names) return written
        // The model has the names in upper case, and a file may write them as it likes.
        names.firstOrNull { it.equals(written, ignoreCase = true) }?.let { return it }
        error(path, value, "'$written' is not one of ${names.joinToString(", ")}${Suggest.hint(written, names)}")
        return written
    }

    private fun list(descriptor: SerialDescriptor, value: ConfigValue, path: String, within: Within?): Any? {
        val element = descriptor.getElementDescriptor(0)
        // One entry may be written without the brackets: `slots = 5`, `actions = { type = close }`.
        val entries = if (value is ConfigList) value.toList() else listOf(value)
        return entries.mapIndexed { index, entry -> walk(element, entry, "$path[$index]", within) }
    }

    private fun map(descriptor: SerialDescriptor, value: ConfigValue, path: String): Any? {
        if (value !is ConfigObject) {
            error(path, value, "should be a set of names with their values")
            return null
        }
        val element = descriptor.getElementDescriptor(1)
        return LinkedHashMap<String, Any?>().also { out -> for (entry in value.entries) out[entry.key] = walk(element, entry.value, join(path, entry.key)) }
    }

    private fun polymorphic(descriptor: SerialDescriptor, value: ConfigValue, path: String): Any? {
        if (value !is ConfigObject) {
            error(path, value, "should be an object with a 'type'")
            return null
        }
        val base = baseName(descriptor)
        val typeValue = value["type"]
        val type = typeValue?.takeIf { it.valueType() == ConfigValueType.STRING }?.unwrapped() as? String
        if (type == null) {
            error(path, value, "needs a 'type' (the kind of $base)")
            return value.unwrapped()
        }
        // What another plugin added is read by that plugin.
        if (type in externals || type in activators) return value.unwrapped()
        val variants = variantsOf(descriptor)
        val variant = variants.firstOrNull { it.serialName == type }
        if (variant == null) {
            val names = variants.map { it.serialName }
            val hint = Suggest.hint(type, names)
            error(join(path, "type"), typeValue, "'$type' is not ${if (base.first() in "aeiou") "an" else "a"} $base${if (type.contains(':')) " (is the plugin that adds it installed?)" else ""}${hint.ifEmpty { " (see the list in the README)" }}")
            return value.unwrapped()
        }
        return structure(variant, value, path, skip = "type").let { LinkedHashMap<String, Any?>(it as Map<String, Any?>).also { out -> out["type"] = type } }
    }

    private fun variantsOf(descriptor: SerialDescriptor): List<SerialDescriptor> = when (descriptor.kind) {
        PolymorphicKind.SEALED -> descriptor.getElementDescriptor(1).elementDescriptors.toList()
        else -> module.getPolymorphicDescriptors(descriptor)
    }

    /** `Action` for `kotlinx.serialization.Polymorphic<Action>` or `net.xalitoria.menu.Action`, in lower case for a sentence. */
    private fun baseName(descriptor: SerialDescriptor): String =
        descriptor.serialName.substringAfter('<').substringBefore('>').substringAfterLast('.').lowercase()

    private fun structure(descriptor: SerialDescriptor, value: ConfigValue, path: String, skip: String?): Any? {
        if (value !is ConfigObject) {
            error(path, value, "should be an object with keys, not ${describe(value)}")
            return null
        }
        val names = (0 until descriptor.elementsCount).map { kebab(descriptor.getElementName(it)) }
        val out = LinkedHashMap<String, Any?>()
        for (entry in value.entries) {
            if (entry.key == skip) continue
            val index = names.indexOf(entry.key)
            if (index < 0) {
                warn(join(path, entry.key), entry.value, "is not a key of ${what(descriptor)}, so it is ignored${Suggest.hint(entry.key, names.filter { it !in value.keys })}")
                continue
            }
            out[entry.key] = walk(descriptor.getElementDescriptor(index), entry.value, join(path, entry.key), descriptor.getElementAnnotations(index).filterIsInstance<Within>().firstOrNull())
        }
        for (index in 0 until descriptor.elementsCount) {
            if (!descriptor.isElementOptional(index) && names[index] !in value.keys) error(join(path, names[index]), value, "is required")
        }
        return out
    }

    private fun what(descriptor: SerialDescriptor): String {
        val name = descriptor.serialName.removeSuffix("?").substringAfterLast('.')
        return if (name == "Menu") "a menu" else if (name == "Item") "an item" else "'$name'"
    }

    private fun describe(value: ConfigValue) = when (value.valueType()) {
        ConfigValueType.LIST -> "a list"
        else -> "'${value.unwrapped()}'"
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    companion object {
        /** `maxStackSize` as the file writes it, `max-stack-size`. */
        fun kebab(property: String): String = buildString {
            for ([i, c] in property.withIndex()) if (c.isUpperCase()) { if (i > 0) append('-'); append(c.lowercaseChar()) } else append(c)
        }
    }
}

/** Finding the word a mistyped one was meant to be. */
object Suggest {
    /** The candidate that [word] is probably a misspelling of, or null: the same letters in another style (`max_stack_size`), else the closest few edits away. */
    fun closest(word: String, candidates: Collection<String>): String? {
        val plain = { text: String -> text.lowercase().filter { it.isLetterOrDigit() } }
        candidates.firstOrNull { plain(it) == plain(word) && it != word }?.let { return it }
        val limit = if (word.length <= 4) 1 else 2
        return candidates.map { it to distance(word.lowercase(), it.lowercase()) }.filter { it.second in 1..limit }.minByOrNull { it.second }?.first
    }

    /** ` (did you mean 'slot'?)`, or nothing. */
    fun hint(word: String, candidates: Collection<String>): String = closest(word, candidates)?.let { " (did you mean '$it'?)" }.orEmpty()

    /** The number of single-letter changes that turn [a] into [b]. */
    fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            for (j in 1..b.length) row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            previous = row
        }
        return previous[b.length]
    }
}
