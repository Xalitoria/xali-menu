package mc.xalitoria.menu.paper.deluxemenus

import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.paper.action.Actions

private val TAG = Regex("""^\s*\[([A-Za-z]+)]\s*(.*)$""")
private val DELAY = Regex("""<delay=(\d+)>""", RegexOption.IGNORE_CASE)
private val CHANCE = Regex("""<chance=(\d+(?:\.\d+)?)%?>""", RegexOption.IGNORE_CASE)

/**
 * Turns a DeluxeMenus command line such as `[player] spawn` or `[message] <delay=20> Hello` into an [Action], or
 * null with a [warn] when XaliMenu has no such action. `<delay=ticks>` and `<chance=percent>` anywhere in the line
 * wrap the action.
 */
internal fun parseAction(line: String, warn: (String) -> Unit): Action? {
    val match = TAG.matchEntire(line)
    if (match == null) {
        warn("command '$line' has no [type], so it is skipped")
        return null
    }
    val tag = match.groupValues[1].lowercase()
    var argument = match.groupValues[2]

    val delay = DELAY.find(argument)?.groupValues?.get(1)?.toLong()
    val chance = CHANCE.find(argument)?.groupValues?.get(1)?.toDouble()
    argument = argument.replace(DELAY, "").replace(CHANCE, "").trim()

    val action = when (tag) {
        "player", "commandevent" -> Actions.Player(argument.removePrefix("/"))
        "console" -> Actions.Console(argument.removePrefix("/"))
        "chat" -> Actions.Chat(argument)
        "message", "minimessage" -> Actions.Message(argument)
        "broadcast", "minibroadcast" -> Actions.Broadcast(argument)
        "actionbar" -> Actions.ActionBar(argument)
        "json" -> Actions.Json(argument)
        "broadcastjson" -> Actions.Json(argument, broadcast = true)
        "openguimenu", "openmenu" -> argument.split(' ').filter { it.isNotEmpty() }.let {
            if (it.isEmpty()) null else Actions.Open(it.first(), it.drop(1))
        }
        "close" -> Actions.Close
        "refresh" -> Actions.Refresh
        "connect" -> Actions.Connect(argument)
        "givemoney" -> Actions.Money(argument)
        "takemoney" -> Actions.Money(argument, take = true)
        "giveexp" -> Actions.Experience(argument)
        "takeexp" -> Actions.Experience(argument, take = true)
        "givepermission" -> Actions.Permission(argument)
        "takepermission" -> Actions.Permission(argument, take = true)
        "sound" -> sound(argument, PLAYER)
        "broadcastsound" -> sound(argument, SERVER)
        "broadcastsoundworld" -> sound(argument, WORLD)
        else -> {
            warn("command type [$tag] isn't supported, so '$line' is skipped")
            return null
        }
    }
    if (action == null) {
        warn("command '$line' is missing its argument, so it is skipped")
        return null
    }

    return action
        .let { if (chance != null) Actions.Chance(chance, it) else it }
        .let { if (delay != null) Actions.Delay(delay, it) else it }
}

private fun sound(argument: String, scope: Actions.Sound.Scope): Actions.Sound? {
    val [name, volume, pitch] = argument.split(' ').filter { it.isNotEmpty() }.let {
        Triple(it.getOrNull(0), it.getOrNull(1)?.toFloatOrNull() ?: 1f, it.getOrNull(2)?.toFloatOrNull() ?: 1f)
    }
    return name?.let { Actions.Sound(it, volume, pitch, scope) }
}
