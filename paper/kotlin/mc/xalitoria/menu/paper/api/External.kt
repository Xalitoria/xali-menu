package mc.xalitoria.menu.paper.api

import com.typesafe.config.ConfigFactory
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import mc.xalitoria.menu.api.Settings

/**
 * Reads an action or condition that another plugin added. The plugin gave no serializer, only a function from the keys
 * written in the menu file ([Settings]) to the object, so the menu is read in two steps: before it is decoded,
 * [packExternals][mc.xalitoria.menu.paper.format.packExternals] turns each such object into `{ type, values }` with the
 * keys as one JSON text, and this builds the object from that.
 */
internal class ExternalSerializer<B : Any>(name: String, private val create: (Settings) -> B) : DeserializationStrategy<B> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(name) { element<String>("values") }

    override fun deserialize(decoder: Decoder): B {
        var values = "{}"
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> values = decodeStringElement(descriptor, 0)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected element $index")
                }
            }
        }
        return create(Settings(ConfigFactory.parseString(values).root().unwrapped()))
    }
}
