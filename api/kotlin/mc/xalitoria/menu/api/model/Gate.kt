package mc.xalitoria.menu.api.model

import kotlinx.serialization.Serializable
import mc.xalitoria.menu.api.Condition
import mc.xalitoria.menu.api.Action

/** A [Condition] with what to run when it fails or holds. A check marked [optional] may fail, as long as [Gate.minimum] checks hold. */
@Serializable
data class Check(
    val condition: Condition,
    val optional: Boolean = false,
    val pass: List<Action> = [],
    val deny: List<Action> = [],
)

/**
 * A set of checks that decide whether something happens: opening a menu, showing an item, or a click doing its
 * actions. All the [require] checks must hold, except the [Check.optional] ones, and at least [minimum] of the checks must
 * (the optional ones count too; to accept any two of three, mark the three optional with a minimum of 2). With
 * [stopAtSuccess] the checks after the [minimum]th that holds are not looked at. [pass] and [deny] run for the gate as a
 * whole, after the checks' own.
 */
@Serializable
data class Gate(
    val require: List<Check> = [],
    val minimum: Int? = null,
    val stopAtSuccess: Boolean = false,
    val pass: List<Action> = [],
    val deny: List<Action> = [],
)
