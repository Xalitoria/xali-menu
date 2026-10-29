package mc.xalitoria.menu.paper.deluxemenus

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import mc.xalitoria.menu.paper.format.MenuFormat
import mc.xalitoria.menu.api.model.Menu

/** A menu read from DeluxeMenus: [name] as it is listed in `config.yml`, and the [warnings] met on the way. */
class ImportedMenu(val name: String, val source: String, val menu: Menu, val warnings: List<String>) {
    /** The name as a file name, `<fileName>.conf`. */
    val fileName: String get() = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}

/** What [DeluxeMenusImporter.read] found: the menus that came across and a message for each one that didn't. */
class ImportReport(val menus: List<ImportedMenu>, val failures: List<String>)

/** What [DeluxeMenusImporter.write] did with each menu. */
class WriteReport(val written: List<Path>, val skipped: List<Path>)

/**
 * Brings a DeluxeMenus folder across: `config.yml` lists the menus under `gui_menus`, each either written out there
 * or kept in a file of the `gui_menus` folder named by `file`. Menus the file doesn't list are not menus to
 * DeluxeMenus, so they are left out here as well.
 */
class DeluxeMenusImporter(private val format: MenuFormat, private val materials: Materials = Materials.None) {
    private val yaml = Yaml.default

    /** Reads every menu `config.yml` of [folder] lists. Nothing is written. */
    fun read(folder: Path): ImportReport {
        val config = folder.resolve("config.yml")
        if (!config.isRegularFile()) return ImportReport([], ["${config.fileName} not found in $folder"])

        val listed = try {
            yaml.parseToYamlNode(config.readText()).map()?.option("gui_menus").map()
        } catch (e: YamlException) {
            return ImportReport([], ["config.yml can't be read: ${e.message}"])
        }
        if (listed == null) return ImportReport([], ["config.yml has no gui_menus section"])

        val menus = mutableListOf<ImportedMenu>()
        val failures = mutableListOf<String>()
        for ((key, value) in listed.entries) {
            val name = key.content
            val entry = value.map()
            if (entry == null) {
                failures += "$name: not a section"
                continue
            }
            val file = entry.option("file").text()
            val [source, section] = if (file == null) {
                "config.yml" to entry
            } else {
                val path = folder.resolve("gui_menus").resolve(file)
                if (!path.isRegularFile()) {
                    failures += "$name: gui_menus/$file not found"
                    continue
                }
                val parsed = try {
                    yaml.parseToYamlNode(path.readText()).map()
                } catch (e: YamlException) {
                    failures += "$name: gui_menus/$file can't be read: ${e.message}"
                    continue
                }
                if (parsed == null) {
                    failures += "$name: gui_menus/$file is not a menu"
                    continue
                }
                "gui_menus/$file" to parsed
            }

            val context = Context(materials)
            val menu = context.convertMenu(section)
            menus += ImportedMenu(name, source, menu, context.warnings)
        }
        return ImportReport(menus, failures)
    }

    /**
     * Writes each menu of [report] to `<target>/<name>.conf`. A menu whose file already exists is left alone unless
     * [overwrite] is set, so importing twice doesn't undo edits.
     */
    fun write(report: ImportReport, target: Path, overwrite: Boolean = false): WriteReport {
        target.createDirectories()
        val written = mutableListOf<Path>()
        val skipped = mutableListOf<Path>()
        for (imported in report.menus) {
            val path = target.resolve("${imported.fileName}.conf")
            if (path.exists() && !overwrite) {
                skipped.add(path)
                continue
            }
            val header = ["Imported from DeluxeMenus: ${imported.source}"] +
                (if (imported.warnings.isEmpty()) [] else ["", "Check these, they didn't come across as they were:"] +
                    imported.warnings.map { "- $it" })
            path.writeText(format.encode(imported.menu, header))
            written.add(path)
        }
        return WriteReport(written, skipped)
    }
}
