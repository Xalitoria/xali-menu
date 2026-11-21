package com.xalitoria.menu.editor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The shape of `schema.json`, which the editor's form and its checks are built from. The file is written from these classes and the tests read it back into
 * them, so what the page expects (`web/src/schema.js`) and what the builder writes are one definition. A type is written with its `kind` (the class
 * discriminator), and a key with nothing to say is left out.
 */
@Serializable
data class SchemaFile(
    val version: String,
    /** The name of the type a menu file is. */
    val root: String,
    /** Every class a menu file can contain, by name (`Item`, `Action:close`...). */
    val types: Map<String, TypeDef>,
    /** What the elements of each built-in catalog hold: the name of each value, with an example. */
    val catalogs: Map<String, Map<String, String>>,
    /** The variants of each base (`Action`, `Condition`, `Catalog`, `Activator`): the name written in `type`, to the name in [types]. */
    val poly: Map<String, Map<String, String>>,
)

@Serializable
data class TypeDef(val doc: String? = null, val fields: List<FieldDef>)

@Serializable
data class FieldDef(
    /** The key as a menu file writes it, in kebab-case. */
    val key: String,
    val type: FieldType,
    val required: Boolean,
    /** The default, when the source shows it as a literal. */
    val default: JsonElement? = null,
    /** The limits of a number, or of each number in a list, from `@Within` or `@WithinRange` on the model. */
    val min: JsonPrimitive? = null,
    val max: JsonPrimitive? = null,
    val doc: String? = null,
)

/** The type of a key. A `poly` is an object with a `type` key that names one of the variants of its [PolyType.base]. */
@Serializable
sealed interface FieldType {
    @Serializable @SerialName("string") data object Text : FieldType
    @Serializable @SerialName("int") data object Whole : FieldType
    @Serializable @SerialName("number") data object Decimal : FieldType
    @Serializable @SerialName("boolean") data object Switch : FieldType
    @Serializable @SerialName("duration") data object Time : FieldType
    /** What the editor does not know how to draw; it is shown as JSON. */
    @Serializable @SerialName("any") data object Anything : FieldType
}

/** A number or a switch that may also be written as text with placeholders; [of] is `int` or `boolean`. */
@Serializable @SerialName("dynamic") data class DynamicType(val of: String) : FieldType

@Serializable @SerialName("enum") data class EnumType(val values: List<String>) : FieldType

@Serializable @SerialName("list") data class ListType(val of: FieldType) : FieldType

@Serializable @SerialName("map") data class MapType(val of: FieldType) : FieldType

/** An object of the type called [type] in [SchemaFile.types]. */
@Serializable @SerialName("object") data class ObjectType(val type: String) : FieldType

@Serializable @SerialName("poly") data class PolyType(val base: String) : FieldType

/** How the schema is written: `kind` tells the types apart, and a key that is null is left out. */
val schemaJson = Json { classDiscriminator = "kind"; prettyPrint = true; prettyPrintIndent = "  "; explicitNulls = false }
