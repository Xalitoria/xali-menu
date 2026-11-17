package net.xalitoria.menu.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A whole number that a menu file writes either as a number (`amount = 3`) or as text with placeholders
 * (`amount = "%vault_eco_balance%"`) that works out to a number each time the item is drawn. [text] is what was written.
 */
@Serializable(with = DynamicInt.Serial::class)
data class DynamicInt(val text: String) {
    constructor(value: Int) : this(value.toString())

    /** The number, when it is written as one; null while it holds placeholders. */
    val fixed: Int? get() = text.trim().toIntOrNull()

    /** The number it works out to once [parse] has filled in the placeholders, or null if that is not a number. */
    fun resolve(parse: (String) -> String): Int? = fixed ?: parse(text).trim().toDoubleOrNull()?.toInt()

    object Serial : KSerializer<DynamicInt> {
        override val descriptor = PrimitiveSerialDescriptor("net.xalitoria.menu.model.DynamicInt", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: DynamicInt) {
            val number = value.fixed
            if (number != null) encoder.encodeInt(number) else encoder.encodeString(value.text)
        }

        override fun deserialize(decoder: Decoder): DynamicInt = DynamicInt(decoder.decodeString())
    }
}

/**
 * A switch that a menu file writes either as `true` or `false`, or as text with placeholders that works out to one of them
 * each time the item is drawn (`glow = "%player_is_op%"`). [text] is what was written.
 */
@Serializable(with = DynamicBoolean.Serial::class)
data class DynamicBoolean(val text: String) {
    constructor(value: Boolean) : this(value.toString())

    /** The value, when it is written as one; null while it holds placeholders. */
    val fixed: Boolean? get() = of(text)

    /** The value it works out to once [parse] has filled in the placeholders, or null if that is not yes or no. */
    fun resolve(parse: (String) -> String): Boolean? = fixed ?: of(parse(text))

    object Serial : KSerializer<DynamicBoolean> {
        override val descriptor = PrimitiveSerialDescriptor("net.xalitoria.menu.model.DynamicBoolean", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: DynamicBoolean) {
            val fixed = value.fixed
            if (fixed != null) encoder.encodeBoolean(fixed) else encoder.encodeString(value.text)
        }

        override fun deserialize(decoder: Decoder): DynamicBoolean = DynamicBoolean(decoder.decodeString())
    }

    private companion object {
        fun of(text: String): Boolean? = when (text.trim().lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> null
        }
    }
}
