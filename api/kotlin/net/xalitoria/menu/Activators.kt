package net.xalitoria.menu

import org.bukkit.entity.Player

/**
 * How a plugin's own activator is built from a menu file, registered with [XaliMenuApi.registerActivator]:
 * `open-with = [{ type = "myplugin:emote", name = "wave" }]` calls [create] with the keys next to `type`. Throw an
 * [IllegalArgumentException] for a bad key; the menu is reported as broken for it.
 */
fun interface ActivatorType {
    fun create(settings: Settings): ActivatorFilter
}

/** What one activator of a menu file asks of the things that happen. */
fun interface ActivatorFilter {
    /**
     * Whether this activator is for what happened, described by [values] as they were given to [ActivatorSource.fire]. It is
     * called on the thread that fired, for every activator of the type, so it must be cheap and must not change anything.
     */
    fun matches(values: Map<String, String>): Boolean
}

/**
 * What a plugin uses to say that something happened to a player, from [XaliMenuApi.registerActivator]. The activators of
 * the menus that match open their menus, as the built-in ones do.
 *
 * ```kotlin
 * val emotes = XaliMenuApi.get().registerActivator(plugin, "myplugin:emote") { settings ->
 *     val wanted = settings.requireString("name")
 *     ActivatorFilter { it["emote"] == wanted }
 * }
 * // when a player does one:
 * if (emotes.fire(player, mapOf("emote" to "wave"))) event.isCancelled = true
 * ```
 */
fun interface ActivatorSource {
    /**
     * Tells the menus that [player] did something described by [values]. The `require` gate of the matching activators is
     * asked, and the menu opened, on the player's own thread soon after; the values reach the gate and the menu's `args` as
     * `{name}`, so `emote` above is `{emote}`. This may be called from any thread. Players can make up the values (a chat
     * message), so they are cleaned before they are put into text, as the arguments of a command are.
     *
     * @return whether the first matching activator asks for the event to be canceled (`cancel`, true unless the menu says
     *   otherwise); the gate is not known yet, so it may not open its menu. False when none matches.
     */
    fun fire(player: Player, values: Map<String, String>): Boolean
}
