package mc.xalitoria.menu.paper.hook

import java.math.BigDecimal
import net.milkbowl.vault2.economy.Economy
import net.milkbowl.vault.permission.Permission
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * The economy and permission providers VaultUnlocked offers. They are looked up on every use: providers such as
 * EssentialsX register them when they enable, which can be after this plugin has. Permissions go through the original
 * Vault interface, which VaultUnlocked keeps and permission plugins such as LuckPerms still register.
 *
 * VaultUnlocked has several currencies; the one named is used, and the economy's default one without a name.
 */
class Economy(private val plugin: Plugin) {
    // Neither Vault nor VaultUnlocked is required: without them their classes aren't there, and naming one throws.
    private val hasEconomyApi = present("net.milkbowl.vault2.economy.Economy")
    private val hasPermissionApi = present("net.milkbowl.vault.permission.Permission")

    private val economy: Economy? get() = if (hasEconomyApi) plugin.server.servicesManager.load(Economy::class.java) else null
    @Suppress("DEPRECATION") // VaultUnlocked deprecates the Vault permission API but still provides it
    private val permission: Permission? get() = if (hasPermissionApi) plugin.server.servicesManager.load(Permission::class.java) else null

    private fun present(className: String) = runCatching { Class.forName(className, false, plugin.javaClass.classLoader) }.isSuccess

    val available: Boolean get() = economy != null

    fun has(player: Player, amount: BigDecimal, currency: String?): Boolean {
        val economy = economy ?: return false
        return if (currency == null) economy.has(plugin.name, player.uniqueId, amount)
        else economy.has(plugin.name, player.uniqueId, player.world.name, currency, amount)
    }

    /** Whether the whole of [amount] was taken. */
    fun withdraw(player: Player, amount: BigDecimal, currency: String?): Boolean {
        val economy = economy ?: return false
        return if (currency == null) economy.withdraw(plugin.name, player.uniqueId, amount).transactionSuccess()
        else economy.withdraw(plugin.name, player.uniqueId, player.world.name, currency, amount).transactionSuccess()
    }

    fun deposit(player: Player, amount: BigDecimal, currency: String?): Boolean {
        val economy = economy ?: return false
        return if (currency == null) economy.deposit(plugin.name, player.uniqueId, amount).transactionSuccess()
        else economy.deposit(plugin.name, player.uniqueId, player.world.name, currency, amount).transactionSuccess()
    }

    /** Whether a permission provider took the change. */
    fun permission(player: Player, node: String, take: Boolean): Boolean {
        val permission = permission ?: return false
        return if (take) permission.playerRemove(player, node) else permission.playerAdd(player, node)
    }
}
