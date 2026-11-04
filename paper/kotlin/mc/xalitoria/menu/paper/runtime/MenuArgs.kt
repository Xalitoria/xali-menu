package mc.xalitoria.menu.paper.runtime

/**
 * The words typed after a menu's command, matched with the names the menu file gives in `args`. Each name takes one word;
 * a last name ending in `...` (`reason...`) takes the rest of the line. A player types these words, so a name that is not
 * marked cannot be given more than one, which is what keeps `{target}` in a console command from adding arguments to it.
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

    /** The names of [complete] that are not one of the [args]. */
    fun unknown(args: List<String>, complete: Map<String, List<String>>): List<String> = (complete.keys - args.map(::name).toSet()).toList()

    /**
     * What to offer for the last of [words], the one being typed (empty after a space): the entries of [complete] for the
     * argument it is, with `@name` entries turned into words by [expand]. A name that takes the rest of the line keeps
     * offering for every word after it; words beyond the names get nothing.
     */
    fun suggestions(args: List<String>, complete: Map<String, List<String>>, words: List<String>, expand: (String) -> Collection<String>): List<String> {
        val index = words.lastIndex
        val spec = args.getOrNull(index) ?: args.lastOrNull()?.takeIf { it.endsWith(REST) } ?: return emptyList()
        return (complete[name(spec)] ?: return emptyList()).flatMap { if (it.startsWith("@")) expand(it) else listOf(it) }
            .filter { it.startsWith(words.last(), ignoreCase = true) }
    }

    /** `<player> <reason...>` for a usage message. */
    fun usage(args: List<String>): String = args.joinToString(" ") { "<$it>" }

    /**
     * The arguments by name for the typed [words], or null if they do not fit: too few, or more than the names take. A
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
