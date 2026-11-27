package mc.xalitoria.menu.paper.format

import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigIncludeContext
import com.typesafe.config.ConfigIncluder
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import com.typesafe.config.ConfigSyntax
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
class MenuFormat(
    private val module: SerializersModule,
    private val externals: Set<String> = emptySet(),
    private val activators: Set<String> = emptySet(),
    private val properties: Set<String> = emptySet(),
) {
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
        val root = ConfigFactory.parseString(text, SAFE_PARSE).resolve(SAFE_RESOLVE).root()
        val checked = ConfigChecker(module, externals, activators, properties).check(root)
        if (checked.errors.isNotEmpty()) throw MenuFormatException(checked.errors, checked.warnings)
        // Only a menu that uses something another plugin added is rewritten; any other is read as written.
        val packed = if ((externals + activators).none { it in text }) checked.config else checked.config.packExternals(externals, activators) as ConfigObject
        return Read(hocon.decodeFromConfig(packed.toConfig()), checked.warnings)
    }

    companion object {
        private val NO_INCLUDE = object : ConfigIncluder {
            override fun withFallback(fallback: ConfigIncluder?) = this
            override fun include(context: ConfigIncludeContext, what: String): ConfigObject =
                throw ConfigException.Generic("include directives are not allowed in menus")
        }
        internal val SAFE_PARSE: ConfigParseOptions = ConfigParseOptions.defaults().setIncluder(NO_INCLUDE)
        internal val JSON_PARSE: ConfigParseOptions = ConfigParseOptions.defaults().setSyntax(ConfigSyntax.JSON).setIncluder(NO_INCLUDE)
        internal val SAFE_RESOLVE: ConfigResolveOptions = ConfigResolveOptions.noSystem()
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
