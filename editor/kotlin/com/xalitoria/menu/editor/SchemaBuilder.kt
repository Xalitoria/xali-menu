package com.xalitoria.menu.editor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.descriptors.getPolymorphicDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import net.xalitoria.menu.Action
import net.xalitoria.menu.Catalog
import net.xalitoria.menu.Condition
import net.xalitoria.menu.model.Activator
import net.xalitoria.menu.model.Menu

/**
 * Describes the menu format as JSON for the editor: every class that a menu file can contain, with the keys it takes, the
 * type and default of each key, and the comment from the source.
 *
 * The structure comes from the serializer descriptors, which are what reads the files, so it cannot differ from the
 * format. The comments come from [SourceDocs]. The result has this shape:
 *
 * ```json
 * { "root": "Menu",
 *   "types": { "Menu": { "doc": "...", "fields": [ { "key": "title", "type": { "kind": "string" }, "required": true, "doc": "..." } ] } },
 *   "poly": { "Action": { "close": "Action:close" } },
 *   "catalogs": { "players": { "name": "Steve" } } }
 * ```
 *
 * A type is a `kind` (`string`, `int`, `number`, `boolean`, `duration`, `dynamic` with `of` `int` or `boolean`, `enum` with `values`, `list` and `map` with `of`,
 * `object` with the name of a type, `poly` with the name of a base) and nothing else. An object of a polymorphic base is
 * written with a `type` key whose value is a name of `poly`.
 */
@OptIn(ExperimentalSerializationApi::class)
class SchemaBuilder(
    private val module: SerializersModule,
    private val docs: SourceDocs,
    private val version: String,
    /** What the elements of each built-in catalog hold: name of each value to an example. */
    private val catalogs: Map<String, Map<String, String>> = emptyMap(),
) {
    private val types = sortedMapOf<String, JsonElement>()
    private val bases = linkedMapOf<String, Map<String, String>>()

    fun build(): JsonObject {
        val root = describe(Menu.serializer().descriptor)
        val baseDescriptors = mapOf(
            "Action" to PolymorphicSerializer(Action::class).descriptor,
            "Condition" to PolymorphicSerializer(Condition::class).descriptor,
            "Catalog" to PolymorphicSerializer(Catalog::class).descriptor,
            "Activator" to serializer<Activator>().descriptor,
        )
        // Walking a variant can reach more bases and more variants, so go on until nothing is new.
        for (entry in baseDescriptors) base(entry.key, entry.value)
        return buildJsonObject {
            put("version", version)
            put("root", root)
            put("types", JsonObject(types))
            put("catalogs", JsonObject(catalogs.toSortedMap().mapValues { entry -> JsonObject(entry.value.mapValues { JsonPrimitive(it.value) }) }))
            put("poly", JsonObject(bases.toSortedMap().mapValues { entry -> JsonObject(entry.value.toSortedMap().mapValues { JsonPrimitive(it.value) }) }))
        }
    }

    /** Registers the base called [name] with its variants, and the variants as types called `Name:variant`. */
    private fun base(name: String, descriptor: SerialDescriptor) {
        if (name in bases) return
        bases[name] = emptyMap()
        val variants = when (descriptor.kind) {
            PolymorphicKind.SEALED -> descriptor.getElementDescriptor(1).elementDescriptors.toList()
            else -> module.getPolymorphicDescriptors(descriptor)
        }
        bases[name] = variants.associate { variant -> variant.serialName to define("$name:${variant.serialName}", variant, base = name) }
    }

    private fun describe(descriptor: SerialDescriptor): String = define(typeId(descriptor), descriptor)

    private fun typeId(descriptor: SerialDescriptor): String = descriptor.serialName.removeSuffix("?").substringAfterLast('.')

    private fun define(id: String, descriptor: SerialDescriptor, base: String? = null): String {
        if (id in types) return id
        types[id] = JsonNull // Breaks a cycle of classes that refer to each other.
        val source = docs.find(descriptor.serialName.removeSuffix("?"), base)
        val fields = (0 until descriptor.elementsCount).map { index ->
            val property = descriptor.getElementName(index)
            val documented = source?.fields?.get(property)
            buildJsonObject {
                put("key", kebab(property))
                put("type", typeOf(descriptor.getElementDescriptor(index)))
                put("required", !descriptor.isElementOptional(index))
                documented?.default?.let { literal(it) }?.let { put("default", it) }
                documented?.doc?.takeIf { it.isNotEmpty() }?.let { put("doc", it) }
            }
        }
        types[id] = buildJsonObject {
            source?.doc?.takeIf { it.isNotEmpty() }?.let { put("doc", it) }
            put("fields", JsonArray(fields))
        }
        return id
    }

    private fun typeOf(descriptor: SerialDescriptor): JsonObject = when {
        descriptor.serialName.contains("Duration") -> kind("duration")
        // A number or a switch that may be written as text with placeholders: the editor offers a box that takes either.
        descriptor.serialName.contains("DynamicInt") -> buildJsonObject { put("kind", "dynamic"); put("of", "int") }
        descriptor.serialName.contains("DynamicBoolean") -> buildJsonObject { put("kind", "dynamic"); put("of", "boolean") }
        descriptor.kind is PrimitiveKind -> kind(
            when (descriptor.kind) {
                PrimitiveKind.BOOLEAN -> "boolean"
                PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "int"
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
                else -> "string"
            },
        )
        descriptor.kind == SerialKind.ENUM -> buildJsonObject {
            put("kind", "enum")
            put("values", JsonArray(descriptor.elementNames.map { JsonPrimitive(it) }))
        }
        descriptor.kind == StructureKind.LIST -> buildJsonObject {
            put("kind", "list")
            put("of", typeOf(descriptor.getElementDescriptor(0)))
        }
        descriptor.kind == StructureKind.MAP -> buildJsonObject {
            put("kind", "map")
            put("of", typeOf(descriptor.getElementDescriptor(1)))
        }
        descriptor.kind == PolymorphicKind.OPEN -> {
            val name = descriptor.serialName.substringAfter('<').substringBefore('>')
            base(name, PolymorphicSerializer(baseClass(name)).descriptor)
            buildJsonObject { put("kind", "poly"); put("base", name) }
        }
        descriptor.kind == PolymorphicKind.SEALED -> {
            val name = typeId(descriptor)
            base(name, descriptor)
            buildJsonObject { put("kind", "poly"); put("base", name) }
        }
        descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT ->
            buildJsonObject { put("kind", "object"); put("type", describe(descriptor)) }
        else -> kind("any")
    }

    private fun baseClass(name: String) = when (name) {
        "Action" -> Action::class
        "Condition" -> Condition::class
        "Catalog" -> Catalog::class
        else -> error("The editor does not know the base '$name'")
    }

    private fun kind(name: String) = buildJsonObject { put("kind", name) }

    companion object {
        /** `dynamicAmount` is written `dynamic-amount` in menu files. */
        fun kebab(property: String): String = buildString {
            for ([i, c] in property.withIndex()) {
                if (c.isUpperCase()) { if (i > 0) append('-'); append(c.lowercaseChar()) } else append(c)
            }
        }

        /** The default of a property as JSON, for the literals that can be shown; null for an expression. */
        fun literal(expression: String): JsonElement? {
            val text = expression.trim()
            Regex("""Dynamic\w+\((.*)\)""").matchEntire(text)?.let { return literal(it.groupValues[1]) }
            return when {
                text == "[]" || text == "emptyList()" -> JsonArray(emptyList())
                text == "emptyMap()" -> JsonObject(emptyMap())
                text == "true" -> JsonPrimitive(true)
                text == "false" -> JsonPrimitive(false)
                text.startsWith("\"") && text.endsWith("\"") && text.length >= 2 -> JsonPrimitive(text.substring(1, text.length - 1))
                Regex("""-?\d[\d_]*[Ll]?""").matches(text) -> JsonPrimitive(text.removeSuffix("L").removeSuffix("l").replace("_", "").toLong())
                Regex("""-?\d[\d_]*\.\d+[Ff]?""").matches(text) -> JsonPrimitive(text.removeSuffix("f").removeSuffix("F").replace("_", "").toDouble())
                Regex("""(\d+)\.(seconds|minutes|milliseconds)""").matches(text) -> {
                    val [n, unit] = text.split('.')
                    JsonPrimitive(n + when (unit) { "seconds" -> "s"; "minutes" -> "m"; else -> "ms" })
                }
                Regex("""\w+\.([A-Z_]+)""").matches(text) -> JsonPrimitive(text.substringAfterLast('.'))
                else -> null
            }
        }
    }
}
