package mc.xalitoria.menu.paper.config

import com.charleskorn.kaml.YamlComment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.util.message.formatArgs

/**
 * Every message the plugin sends. Text is colored like in XaliChat: `&` codes, `&#RRGGBB`, CMI gradients such as
 * `{#a0a>}text{#fff<}` and MiniMessage tags all work. `{0}`, `{1} ...` are filled in by the command that sends it.
 */
@Serializable
data class MessageConfig(
    val commands: Commands = Commands(),
) {
    @Serializable
    data class Commands(
        @SerialName("no-permission")
        val noPermission: String = "No permission.",
        private val help: String = """
            {#a0a>}&lXALIM{#fff<}&f&lENU &7> &dHELP
            &d/{0} &rreload
            &d/{0} &rlist
            &d/{0} &ropen &7<menu> [player]
            &d/{0} &rimport deluxemenus &7[overwrite] [folder]
        """.trimIndent(),
        val reload: Reload = Reload(),
        val menu: Menu = Menu(),
        val variable: Variable = Variable(),
        @SerialName("import")
        val importer: Import = Import(),
    ) {
        fun help(label: String): String = help.formatArgs(label)

        @Serializable
        data class Variable(
            private val help: String = """
                {#a0a>}&lXALIM{#fff<}&f&lENU &7> &dVARIABLES
                &d/xvar &rget <name>
                &d/xvar &rset <name> <value> &7[time] [replace]
                &d/xvar &rrem <name>
                &d/xvar &rinc|dec|mul|div <name> <number>
                &d/xvarp &r<player> get|set|rem|inc|dec|mul|div ... &7(same, for one player)
            """.trimIndent(),
            private val unavailable: String = "The variable {0} is not ready yet: its database is still being connected to.",
            private val got: String = "{0} = &f{1}",
            private val unset: String = "{0} is not set.",
            private val set: String = "Set {0} to {1}.",
            private val kept: String = "{0} already exists; it was kept.",
            private val removed: String = "Removed {0}.",
            private val missing: String = "{0} doesn't exist.",
            private val changed: String = "{0} is now {1}.",
            @SerialName("not-a-number")
            private val notANumber: String = "{0} is not a number.",
            @SerialName("invalid-time")
            private val invalidTime: String = "'{0}' is not a time like 30m, 1h or 2d.",
            @SerialName("unknown-player")
            private val unknownPlayer: String = "No player named '{0}' has been here.",
        ) {
            fun help(): String = help
            fun unavailable(name: String): String = unavailable.formatArgs(name)
            fun got(name: String, value: String): String = got.formatArgs(name, value)
            fun unset(name: String): String = unset.formatArgs(name)
            fun set(name: String, value: String): String = set.formatArgs(name, value)
            fun kept(name: String): String = kept.formatArgs(name)
            fun removed(name: String): String = removed.formatArgs(name)
            fun missing(name: String): String = missing.formatArgs(name)
            fun changed(name: String, value: String): String = changed.formatArgs(name, value)
            fun notANumber(text: String): String = notANumber.formatArgs(text)
            fun invalidTime(text: String): String = invalidTime.formatArgs(text)
            fun unknownPlayer(name: String): String = unknownPlayer.formatArgs(name)
        }

        @Serializable
        data class Menu(
            private val unknown: String = "No menu named '{0}'.",
            @SerialName("player-only")
            val playerOnly: String = "You must be a player to use this command.",
            private val usage: String = "Usage: &d/{0} &r{1}",
            private val opened: String = "Opened '{0}' for {1}.",
            @SerialName("target-invalid")
            private val targetInvalid: String = "Player '{0}' not found!",
            private val loaded: String = "Loaded {0} menus.",
            private val list: String = "Menus ({0}): {1}",
            @SerialName("list-empty")
            val listEmpty: String = "No menus are loaded.",
            @SerialName("commands-changed")
            private val commandsChanged: String = "The commands {0} changed; they apply after a restart.",
        ) {
            fun unknown(name: String): String = unknown.formatArgs(name)
            fun usage(label: String, args: String): String = usage.formatArgs(label, args)
            fun opened(menu: String, player: String): String = opened.formatArgs(menu, player)
            fun targetInvalid(name: String): String = targetInvalid.formatArgs(name)
            fun loaded(count: Int): String = loaded.formatArgs(count)
            fun list(count: Int, names: String): String = list.formatArgs(count, names)
            fun commandsChanged(labels: String): String = commandsChanged.formatArgs(labels)
        }

        @Serializable
        data class Reload(
            val pass: String = "{#a0a>}&lXALIM{#fff<}&f&lENU &7> &aReloaded!",
        )

        @Serializable
        data class Import(
            @SerialName("not-a-folder")
            private val notAFolder: String = "{0} is not a folder.",
            private val failed: String = "The import failed: {0}. The console has the details.",
            private val nothing: String = "Nothing to import from {0}.",
            private val imported: String = "Imported {0} of {1} menus into {2}.",
            private val kept: String = "Kept {0} menus that already have a file; add overwrite to replace them: {1}",
            private val notes: String = "{0} things didn't come across as they were; each file lists its own at the top.",
            private val more: String = "...and {0} more.",
            @YamlComment("How many of the notes are shown in chat; the console gets all of them.")
            @SerialName("notes-shown")
            val notesShown: Int = 8,
        ) {
            fun notAFolder(path: String): String = notAFolder.formatArgs(path)
            fun failed(reason: String): String = failed.formatArgs(reason)
            fun nothing(path: String): String = nothing.formatArgs(path)
            fun imported(written: Int, total: Int, path: String): String = imported.formatArgs(written, total, path)
            fun kept(count: Int, files: String): String = kept.formatArgs(count, files)
            fun notes(count: Int): String = notes.formatArgs(count)
            fun more(count: Int): String = more.formatArgs(count)
        }
    }
}
