package mc.xalitoria.menu.api

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.function.UnaryOperator

/** A value kept by [Variables]. [expiry] is when it stops existing; null keeps it until it is removed. */
data class Variable(val name: String, val value: String, val expiry: Instant?) {
    constructor(name: String, value: String) : this(name, value, null)

    fun isExpired(now: Instant): Boolean = expiry != null && !now.isBefore(expiry)

    fun isExpired(): Boolean = isExpired(Instant.now())

    /** The value as a number, if it is one. */
    val number: BigDecimal? get() = parseNumber(value)

    companion object {
        private val NUMBER = Regex("[+-]?\\d{1,40}(\\.\\d{1,40})?")

        /** [text] as a plain decimal number (no exponent, no `NaN`), or null if it isn't one. */
        @JvmStatic
        fun parseNumber(text: String): BigDecimal? = text.trim().takeIf(NUMBER::matches)?.toBigDecimal()

        /** [number] as the text a variable holds: no trailing zeros, no exponent. */
        @JvmStatic
        fun format(number: BigDecimal): String = number.stripTrailingZeros().toPlainString()
    }
}

/** Whose variables: everyone's, or one player's. */
sealed interface Scope {
    data object Global : Scope

    data class Player(val id: UUID) : Scope
}

/**
 * Values that menus keep between clicks and restarts: global ones, shared by everyone, and personal ones, one set per
 * player. In text they are `%xalimenu_var_<name>%` and `%xalimenu_varp_<name>%`; the `/xvar` and `/xvarp` commands and
 * the `variable` action change them.
 *
 * Where a variable is kept is decided by its name, by the rules in `config.yml`: in memory only, in the server's own
 * SQLite file, or in the database every server shares. Names are case-insensitive. Reading is from memory and fine on any
 * thread; a change is in memory at once and written in the background. A variable that has expired reads as absent.
 *
 * A shared (`network`) variable can't be used until the server has connected to the database, which happens in the
 * background after startup: until then [isAvailable] is false, a read finds nothing and a change is refused. It is also
 * only as current as the last poll of the database (a second by default). A change made to one on two servers at once is
 * applied by the database in turn, so numbers add up; `replace = false` is decided by this server's copy first and
 * corrected when the database answers.
 */
interface Variables {
    /** The variable called [name] of [scope], or null if there is none, it has expired, or [isAvailable] is false. */
    operator fun get(scope: Scope, name: String): Variable?

    /**
     * Sets a variable. With [replace] false an existing one is left as it is.
     * @return whether the value was written; false too if the variable isn't [available][isAvailable]
     */
    fun set(scope: Scope, name: String, value: String, expiry: Instant?, replace: Boolean): Boolean

    /** Sets a variable that has no expiry and replaces an existing one. */
    fun set(scope: Scope, name: String, value: String): Boolean

    /** @return whether there was a variable to remove */
    fun remove(scope: Scope, name: String): Boolean

    /**
     * Replaces a numeric variable by what [change] makes of its value (a missing one counts as 0), keeping its expiry.
     * [change] may run more than once, so it must not have effects of its own. Nothing happens, and false comes back, if
     * the value isn't a number or the variable isn't [available][isAvailable].
     */
    fun change(scope: Scope, name: String, change: UnaryOperator<BigDecimal>): Boolean

    /** Whether the variables called [name] can be read and changed now: where they are kept has loaded. */
    fun isAvailable(name: String): Boolean
}
