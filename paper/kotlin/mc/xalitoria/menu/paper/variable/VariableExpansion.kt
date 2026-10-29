package mc.xalitoria.menu.paper.variable

import kotlin.time.Duration.Companion.seconds
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import mc.xalitoria.menu.api.Scope
import mc.xalitoria.menu.api.Variable
import mc.xalitoria.menu.paper.XaliMenu
import org.bukkit.OfflinePlayer
import java.time.Duration
import java.time.Instant

/**
 * The variables for PlaceholderAPI:
 * - `%xalimenu_var_<name>%` a global variable, and `%xalimenu_varp_<name>%` the personal one of the player the text is for;
 * - `%xalimenu_vart_<name>%` and `%xalimenu_varpt_<name>%` the time they have left, such as `1h 2m 3s`, or `0s` if they
 *   don't expire (or don't exist).
 *
 * `:<default>` after the name is what is shown if there is no such variable, `%xalimenu_var_coins:0%`. While the store the
 * variable lives in isn't loaded yet (the shared database is still being connected to) the placeholder isn't answered at
 * all, so it stays in the text as written and tests on it fail.
 */
class VariableExpansion(private val plugin: XaliMenu) : PlaceholderExpansion() {
    override fun getIdentifier() = "xalimenu"

    override fun getAuthor() = plugin.pluginMeta.authors.joinToString()

    override fun getVersion() = plugin.pluginMeta.version

    /** Stays registered when PlaceholderAPI reloads its expansions. */
    override fun persist() = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val kind = params.substringBefore('_')
        val rest = params.substringAfter('_', "")
        if (rest.isEmpty()) return null
        val name = rest.substringBefore(':')
        val default = rest.substringAfter(':', "")
        val variables = plugin.variables
        // Left as it is written, not answered with the default: a test such as "claimed == 0" must not pass for want of an answer.
        if (!variables.isAvailable(name)) return null
        val variable: Variable? = when (kind) {
            "var", "vart" -> variables[Scope.Global, name]
            "varp", "varpt" -> player?.let { variables[Scope.Player(it.uniqueId), name] }
            else -> return null
        }
        return when (kind) {
            "var", "varp" -> variable?.value ?: default
            else -> if (variable == null) default.ifEmpty { "0s" } else remaining(variable)
        }
    }

    private fun remaining(variable: Variable): String {
        val expiry = variable.expiry ?: return "0s"
        val left = Duration.between(Instant.now(), expiry).seconds.coerceAtLeast(0)
        return left.seconds.toString()
    }
}
