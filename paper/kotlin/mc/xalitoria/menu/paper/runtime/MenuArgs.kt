package mc.xalitoria.menu.paper.runtime

/**
 * The words typed after a menu's command, matched with the names the menu file gives in `args`. Each name takes one word;
 * a last name ending in `...` (`reason...`) takes the rest of the line. A player types these words, so a name that isn't
 * marked can't be given more than one, which is what keeps `{target}` in a console command from adding arguments to it.
 */
object MenuArgs {
    private const val REST = "..."

    private fun name(spec: String) = spec.removeSuffix(REST)

    /** What is wrong with the `args` of a menu file, if anything. */
    fun problems(args: List<String>): List<String> = buildList {
        args.forEachIndexed { index, spec ->
            if (name(spec).isBlank() || name(spec).any { it.isWhitespace() || it == '{' || it == '}' }) add("argument '$spec' isn't a plain name")
            if (spec.endsWith(REST) && index != args.lastIndex) add("only the last argument can end in '...' to take the rest of the line, not '$spec'")
        }
        val names = args.map(::name)
        if (names.size != names.toSet().size) add("argument names repeat")
    }

    /** `<player> <reason...>` for a usage message. */
    fun usage(args: List<String>): String = args.joinToString(" ") { "<$it>" }

    /**
     * The arguments by name for the typed [words], or null if they don't fit: too few, or more than the names take. A
     * menu with no `args` takes any words and ignores them.
     */
    fun bind(args: List<String>, words: List<String>): Map<String, String>? {
        if (args.isEmpty()) return emptyMap()
        if (words.size < args.size) return null
        val rest = args.last().endsWith(REST)
        if (words.size > args.size && !rest) return null
        return args.mapIndexed { index, spec ->
            name(spec) to if (rest && index == args.lastIndex) words.drop(index).joinToString(" ") else words[index]
        }.toMap()
    }
}
