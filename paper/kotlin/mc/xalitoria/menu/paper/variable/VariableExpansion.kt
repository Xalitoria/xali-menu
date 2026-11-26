package mc.xalitoria.menu.paper.variable

import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.paper.runtime.Clients
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.xalitoria.menu.Scope
import net.xalitoria.menu.Variable
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * The variables for PlaceholderAPI:
 * - `%xmenu_var_<name>%` a global variable, and `%xmenu_varp_<name>%` the personal one of the player the text is for;
 * - `%xmenu_vart_<name>%` and `%xmenu_varpt_<name>%` the time they have left, such as `1h 2m 3s`, or `0s` if they
 *   do not expire (or do not exist).
 *
 * - `%xmenu_page%`, `%xmenu_pages%`, `%xmenu_total%`, `%xmenu_page_next%` and `%xmenu_page_prev%` for the
 *   player's open menu, when it is paged: the page (from 1), the number of pages, the number of elements, and the pages
 *   after and before this one (this one at the ends).
 *
 * `:<default>` after the name is what is shown if there is no such variable, `%xmenu_var_coins:0%`. While the store the
 * variable lives in is not loaded yet (the shared database is still being connected to) the placeholder is not answered at
 * all, so it stays in the text as written and tests on it fail.
 */
class VariableExpansion(private val plugin: XaliMenu) : PlaceholderExpansion() {
    override fun getIdentifier() = "xmenu"

    override fun getAuthor() = plugin.pluginMeta.authors.joinToString()

    override fun getVersion() = plugin.pluginMeta.version

    /** Stays registered when PlaceholderAPI reloads its expansions. */
    override fun persist() = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (params in PAGE_VALUES) {
            val online = player as? Player ?: return null
            return plugin.menus.view(online)?.pageValues?.get(params)
                ?: plugin.bedrockForms.session(online)?.pageValues?.get(params)
        }
        if (params.startsWith("client_")) {
            val online = player as? Player ?: return null
            val profile = Clients.profile(online)
            return when (params) {
                "client_edition" -> profile.edition.name.lowercase()
                "client_device" -> profile.device.name.lowercase()
                "client_os" -> profile.os.name.lowercase()
                "client_input" -> profile.input.name.lowercase()
                "client_ui" -> profile.ui.name.lowercase()
                else -> null
            }
        }
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

    private companion object {
        val PAGE_VALUES = setOf("page", "pages", "total", "page_next", "page_prev")
    }

    private fun remaining(variable: Variable): String {
        val expiry = variable.expiry ?: return "0s"
        val left = Duration.between(Instant.now(), expiry).seconds.coerceAtLeast(0)
        return left.seconds.toString()
    }
}
