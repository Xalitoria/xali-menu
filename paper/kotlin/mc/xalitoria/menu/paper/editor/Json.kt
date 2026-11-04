package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValueFactory

/** JSON for the editor, through the config library the menus are read with, so there is nothing else to load. */
internal object Json {
    /** [value] (maps, lists, strings, numbers, booleans and null) as JSON text. */
    fun write(value: Any?): String = ConfigValueFactory.fromAnyRef(value).render(ConfigRenderOptions.concise())

    /** The JSON object in [text]; an exception with a readable message if it is not one. */
    fun read(text: String): ConfigObject = ConfigFactory.parseString(text).root()
}
