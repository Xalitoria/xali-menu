package mc.xalitoria.menu.paper.config

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.message.formatArgs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every message the plugin sends. Text is colored like in XaliChat: `&` codes, `&#RRGGBB`, CMI gradients such as
 * `{#a0a>}text{#fff<}` and MiniMessage tags all work. `{0}`, `{1} ...` are filled in by the command that sends it.
 */
@Serializable
data class MessageConfig(
    val commands: Commands = Commands(),
    val input: Input = Input(),
) {
    /** What an `input` action tells a player whose reply was turned down and that the action gave no text for. */
    @Serializable
    data class Input(
        @SerialName("too-long")
        private val tooLong: String = "&cThat is longer than {0} characters.",
        @SerialName("not-accepted")
        val notAccepted: String = "That isn't accepted. Try again.",
    ) {
        fun tooLong(limit: Int): String = tooLong.formatArgs(limit)
    }

    @Serializable
    data class Commands(
        @SerialName("no-permission")
        val noPermission: String = "No permission.",
        private val help: String = """
            {#a0a>}&lXALIM{#fff<}&f&lENU &7> &dHELP
            &d/{0} &rreload &7[menu]
            &d/{0} &rlist
            &d/{0} &rinfo &7<menu>
            &d/{0} &rvalidate &7[menu]
            &d/{0} &rinspect &7[player]
            &d/{0} &ropen &7<menu> [player]
            &d/{0} &redit &7[menu]
            &d/{0} &rimport deluxemenus &7[overwrite] [folder]
        """.trimIndent(),
        val reload: Reload = Reload(),
        val menu: Menu = Menu(),
        val tools: Tools = Tools(),
        val variable: Variable = Variable(),
        val edit: Edit = Edit(),
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

        /** `/xmenu reload <menu>`, `info`, `validate` and `inspect`. */
        @Serializable
        data class Tools(
            @SerialName("reloaded-one")
            private val reloadedOne: String = "Reloaded the menu '{0}'.",
            @SerialName("no-file")
            private val noFile: String = "There is no file for a menu named '{0}'.",
            private val unreadable: String = "The menu '{0}' could not be read, so the old one stays: {1}",
            @SerialName("no-problems")
            private val noProblems: String = "'{0}' has no problems.",
            private val problems: String = "'{0}' has {1} problems:",
            @SerialName("validated")
            private val validated: String = "{0} menus checked; {1} have problems.",
            @SerialName("info-header")
            private val infoHeader: String = "Menu '{0}' ({1})",
            @SerialName("info-line")
            private val infoLine: String = "&7{0}: &r{1}",
            @SerialName("inspect-closed")
            private val inspectClosed: String = "{0} has no menu open.",
            @SerialName("inspect-header")
            private val inspectHeader: String = "{0} has '{1}' open:",
            @SerialName("inspect-more")
            private val inspectMore: String = "...and {0} more lines; the console has all of them.",
            @YamlComment("How many lines of `/xmenu inspect` are shown in chat; the console gets all of them.")
            @SerialName("inspect-shown")
            val inspectShown: Int = 30,
        ) {
            fun reloadedOne(name: String): String = reloadedOne.formatArgs(name)
            fun noFile(name: String): String = noFile.formatArgs(name)
            fun unreadable(name: String, reason: String): String = unreadable.formatArgs(name, reason)
            fun noProblems(name: String): String = noProblems.formatArgs(name)
            fun problems(name: String, count: Int): String = problems.formatArgs(name, count)
            fun validated(menus: Int, bad: Int): String = validated.formatArgs(menus, bad)
            fun infoHeader(name: String, kind: String): String = infoHeader.formatArgs(name, kind)
            fun infoLine(label: String, value: String): String = infoLine.formatArgs(label, value)
            fun inspectClosed(player: String): String = inspectClosed.formatArgs(player)
            fun inspectHeader(player: String, menu: String): String = inspectHeader.formatArgs(player, menu)
            fun inspectMore(count: Int): String = inspectMore.formatArgs(count)
        }

        @Serializable
        data class Edit(
            @SerialName("link-server")
            val linkServer: String = "&aOpen the editor: {link} &7(works for {minutes} minutes)",
            @SerialName("link-data")
            val linkData: String = "&aOpen the menu '{menu}' in the editor: {link}",
            @SerialName("data-hint")
            val dataHint: String = "&7The editor is not connected to this server. Download the file there and put it in the menus folder, then run /xmenu reload <menu>.",
            @SerialName("needs-menu")
            val needsMenu: String = "&cName a menu: /xmenu edit <menu>. The editor is not turned on, so the link carries one menu.",
            @SerialName("too-big")
            private val tooBig: String = "&cThe menu '{0}' is too big for a link. Turn on `editor.enabled` in config.yml, or open the file in the editor yourself.",
            @SerialName("click-to-open")
            val clickToOpen: String = "Click to open the editor",
            @SerialName("not-listening")
            private val notListening: String = "&cThe editor could not listen on {0}: {1}",
            @SerialName("share-link")
            val shareLink: String = "&aShared editor: {link} &7(works for {minutes} minutes). Anyone with it can read the menus and send edits; nothing is written until you apply an edit with /xmenu edits.",
            @SerialName("share-link-readonly")
            val shareLinkReadOnly: String = "&aRead-only editor: {link} &7(works for {minutes} minutes). Anyone with it can read the menus; nothing can be sent back.",
            @SerialName("share-off")
            val shareOff: String = "&cSharing is off: set `editor.relay.url` in config.yml to the address of a relay.",
            @SerialName("share-failed")
            private val shareFailed: String = "&cThe relay could not be reached: {0}",
            @SerialName("share-stopped")
            private val shareStopped: String = "&7Ended {0} shared link(s).",
            @SerialName("edits-none")
            val editsNone: String = "&7No edits are waiting.",
            @SerialName("edits-header")
            private val editsHeader: String = "&eEdits waiting: {0}. &7/xmenu edits apply <id> or drop <id>",
            @SerialName("edits-line")
            private val editsLine: String = "&7#{0} &f{1} &7sent &f{2} &7({3} problem(s), {4} min ago)",
            @SerialName("edits-applied")
            private val editsApplied: String = "&aApplied #{0}: '{1}' is saved and loaded.{2}",
            @SerialName("edits-dropped")
            private val editsDropped: String = "&7Dropped #{0}.",
            @SerialName("edits-unknown")
            private val editsUnknown: String = "&cThere is no edit #{0}.",
            @SerialName("edits-refused")
            private val editsRefused: String = "&c#{0} could not be applied: {1}",
            @SerialName("held-notice")
            private val heldNotice: String = "&e{0} sent an edit of '{1}' (#{2}) through a shared link. /xmenu edits lists it.",
        ) {
            fun shareFailed(reason: String): String = shareFailed.formatArgs(reason)
            fun shareStopped(count: Int): String = shareStopped.formatArgs(count)
            fun editsHeader(count: Int): String = editsHeader.formatArgs(count)
            fun editsLine(id: Int, from: String, menu: String, problems: Int, minutes: Long): String = editsLine.formatArgs(id, from, menu, problems, minutes)
            fun editsApplied(id: Int, menu: String, problems: String): String = editsApplied.formatArgs(id, menu, problems)
            fun editsDropped(id: Int): String = editsDropped.formatArgs(id)
            fun editsUnknown(id: Int): String = editsUnknown.formatArgs(id)
            fun editsRefused(id: Int, reason: String): String = editsRefused.formatArgs(id, reason)
            fun heldNotice(from: String, menu: String, id: Int): String = heldNotice.formatArgs(from, menu, id)
            fun tooBig(menu: String): String = tooBig.formatArgs(menu)
            fun notListening(address: String, reason: String): String = notListening.formatArgs(address, reason)
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
