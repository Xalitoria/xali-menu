package mc.xalitoria.menu.paper.editor

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigRenderOptions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.format.HoconWriter
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.paper.runtime.LoadedMenu
import mc.xalitoria.menu.paper.runtime.MenuManager

/**
 * The menu files as the editor sees them: JSON in, JSON out. The editor edits the keys exactly as they are written in the
 * file, so a menu is read and written as plain config here and not through the model; that keeps what the model does not know
 * (an addon's keys) from being lost. What is saved is still read through the model first, so a menu that would not load is
 * refused before it replaces the file.
 */
internal class EditorMenus(private val plugin: XaliMenu) {
    private val folder: Path get() = plugin.dataPath.resolve("menus")

    /** The names of the menu files. */
    fun names(): List<String> = folder.takeIf { it.isDirectory() }?.let { dir ->
        Files.list(dir).use { files -> files.filter { it.toString().endsWith(".conf") }.map { it.nameWithoutExtension }.sorted().toList() }
    }.orEmpty()

    /** The menu [name] as it is in its file, or null if there is no such file. */
    fun read(name: String): ConfigObject? {
        val file = file(name)?.takeIf { it.exists() } ?: return null
        return ConfigFactory.parseFile(file.toFile()).resolve().root()
    }

    /** What reading and saving the menu came to. */
    sealed interface Result {
        /** The menu is as the model reads it; [problems] are what the plugin finds in it. */
        data class Ok(val problems: List<String>) : Result

        /** The menu cannot be read: [reason] is why. Nothing was written. */
        data class Refused(val reason: String) : Result
    }

    /** Reads [menu] as the plugin would, without writing anything. */
    fun check(name: String, menu: ConfigObject): Result {
        if (!valid(name)) return Result.Refused("'$name' is not a name for a menu: use letters, digits, - and _")
        return try {
            val decoded = format().decode(menu.render(ConfigRenderOptions.concise()))
            Result.Ok(plugin.menus.problemsOf(LoadedMenu(name, decoded)))
        } catch (e: Exception) {
            Result.Refused(generateSequence<Throwable>(e) { it.cause }.last().message ?: e::class.simpleName.orEmpty())
        }
    }

    /** Checks [menu], writes it to the file of [name] (keeping the old one as `<name>.conf.bak`), and loads it. */
    fun save(name: String, menu: ConfigObject): Result {
        val checked = check(name, menu)
        if (checked is Result.Refused) return checked
        val target = file(name) ?: return Result.Refused("'$name' is not a name for a menu")
        Files.createDirectories(folder)
        if (target.exists()) Files.copy(target, target.resolveSibling("${target.fileName}.bak"), StandardCopyOption.REPLACE_EXISTING)
        // Written beside the file and moved over it, so a crash never leaves half a menu.
        val temporary = Files.createTempFile(folder, ".editor-", ".tmp")
        try {
            Files.writeString(temporary, HoconWriter.write(menu))
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return when (val loaded = plugin.menus.reload(name)) {
            is MenuManager.Reread.Done -> Result.Ok(loaded.problems)
            is MenuManager.Reread.Failed -> Result.Refused(loaded.reason)
            MenuManager.Reread.NoFile -> Result.Refused("The file was written but could not be found again")
        }
    }

    private fun format() = MenuFormat(plugin.registry.module, plugin.registry.externalNames, plugin.registry.activatorNames)

    private fun file(name: String): Path? = if (valid(name)) folder.resolve("$name.conf") else null

    private fun valid(name: String) = NAME.matches(name)

    companion object {
        /** Menu names are file names, so they are kept to what cannot leave the folder. */
        val NAME = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
