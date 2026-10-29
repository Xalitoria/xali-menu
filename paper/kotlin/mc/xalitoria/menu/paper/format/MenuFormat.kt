package mc.xalitoria.menu.paper.format

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import kotlinx.serialization.hocon.encodeToConfig
import kotlinx.serialization.modules.SerializersModule
import mc.xalitoria.menu.api.model.Menu

/**
 * Reads and writes menus as HOCON, the format of every file in `menus/`. The actions and conditions in a file are
 * resolved through [module], which holds the built-in ones and whatever other plugins have added.
 */
class MenuFormat(module: SerializersModule, private val externals: Set<String> = emptySet()) {
    private val hocon = Hocon {
        serializersModule = module
        encodeDefaults = false
        // dynamicAmount is written dynamic-amount, like the keys of the YAML files.
        useConfigNamingConvention = true
    }

    fun decode(text: String): Menu {
        val root = ConfigFactory.parseString(text).resolve().root()
        // Only a menu that uses something another plugin added is rewritten; any other is read as written.
        val packed = if (externals.none { it in text }) root else root.packExternals(externals) as ConfigObject
        return hocon.decodeFromConfig(packed.toConfig())
    }

    fun read(path: Path): Menu = decode(Files.readString(path))

    /** The HOCON for [menu], below the lines of [header] written as comments. */
    fun encode(menu: Menu, header: List<String> = []): String {
        val body = HoconWriter.write(hocon.encodeToConfig(menu).root())
        return header.joinToString("") { if (it.isEmpty()) "#\n" else "# $it\n" } + (if (header.isEmpty()) "" else "\n") + body
    }
}
