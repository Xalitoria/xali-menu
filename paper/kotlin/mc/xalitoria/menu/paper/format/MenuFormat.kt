package mc.xalitoria.menu.paper.format

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import kotlinx.serialization.hocon.Hocon
import kotlinx.serialization.hocon.decodeFromConfig
import kotlinx.serialization.hocon.encodeToConfig
import kotlinx.serialization.modules.SerializersModule
import net.xalitoria.menu.model.Menu
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads and writes menus as HOCON, the format of every file in `menus/`. The actions and conditions in a file are
 * resolved through [module], which holds the built-in ones and whatever other plugins have added.
 */
class MenuFormat(private val module: SerializersModule, private val externals: Set<String> = emptySet(), private val activators: Set<String> = emptySet()) {
    private val hocon = Hocon {
        serializersModule = module
        encodeDefaults = false
        // dynamicAmount is written dynamic-amount, like the keys of the YAML files.
        useConfigNamingConvention = true
    }

    /** A menu read from a file, with what the file has that nothing reads or that looks wrong ([warnings]); the file still loads. */
    class Read(val menu: Menu, val warnings: List<ConfigProblem>)

    /**
     * Reads [text] as a menu, saying what is wrong with it by where it is (`items.a.click.left.actions[0].type: 'clse' is not an action (did you mean
     * 'close'?)`) and not by what the reader tripped on. A file that cannot be read throws [MenuFormatException] with every reason at once.
     */
    fun decodeChecked(text: String): Read {
        val root = ConfigFactory.parseString(text).resolve().root()
        val checked = ConfigChecker(module, externals, activators).check(root)
        if (checked.errors.isNotEmpty()) throw MenuFormatException(checked.errors)
        // Only a menu that uses something another plugin added is rewritten; any other is read as written.
        val packed = if ((externals + activators).none { it in text }) checked.config else checked.config.packExternals(externals, activators) as ConfigObject
        return Read(hocon.decodeFromConfig(packed.toConfig()), checked.warnings)
    }

    fun decode(text: String): Menu = decodeChecked(text).menu

    fun read(path: Path): Menu = decode(Files.readString(path))

    fun readChecked(path: Path): Read = decodeChecked(Files.readString(path))

    /** The HOCON for [menu], below the lines of [header] written as comments. */
    fun encode(menu: Menu, header: List<String> = []): String {
        val body = HoconWriter.write(hocon.encodeToConfig(menu).root())
        return header.joinToString("") { if (it.isEmpty()) "#\n" else "# $it\n" } + (if (header.isEmpty()) "" else "\n") + body
    }
}
