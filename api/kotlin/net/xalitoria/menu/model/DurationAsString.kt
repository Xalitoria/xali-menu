package net.xalitoria.menu.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration

/** A [Duration] as the text people write, `1s` or `1m 30s`, instead of the ISO form the built-in serializer uses. */
object DurationAsString : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor("net.xalitoria.menu.model.Duration", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Duration = Duration.parse(decoder.decodeString())
}
