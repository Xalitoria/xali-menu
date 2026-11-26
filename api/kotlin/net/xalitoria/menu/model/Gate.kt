package net.xalitoria.menu.model

import kotlinx.serialization.Serializable
import net.xalitoria.menu.Action
import net.xalitoria.menu.Condition

/** A [Condition] with what to run when it fails or holds. A check marked [optional] may fail, as long as [Gate.minimum] checks hold. */
@Serializable
data class Check(
    val condition: Condition,
    val optional: Boolean = false,
    /** Run when the condition holds. */
    val pass: List<Action> = [],
    /** Run when the condition does not hold. */
    val deny: List<Action> = [],
)

/**
 * A set of checks that decide whether something happens: opening a menu, showing an item, or a click doing its
 * actions. All the [require] checks must hold, except the [Check.optional] ones, and at least [minimum] of the checks must
 * (the optional ones count too; to accept any two of three, mark the three optional with a minimum of 2). With
 * [stopAtSuccess] optional checks after the [minimum]th that holds are not looked at; non-optional checks must still hold.
 * [pass] and [deny] run for the gate as a whole, after the checks' own.
 */
@Serializable
data class Gate(
    /** The checks, tried in order. */
    val require: List<Check> = [],
    /** How many checks must hold, optional ones included. All non-optional checks must hold regardless. */
    val minimum: Int? = null,
    val stopAtSuccess: Boolean = false,
    /** Run when the gate holds. */
    val pass: List<Action> = [],
    /** Run when the gate does not hold. */
    val deny: List<Action> = [],
)
