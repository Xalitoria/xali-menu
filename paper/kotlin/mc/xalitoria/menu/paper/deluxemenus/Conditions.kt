package mc.xalitoria.menu.paper.deluxemenus

import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import mc.xalitoria.menu.api.Action
import mc.xalitoria.menu.api.model.Check
import mc.xalitoria.menu.api.Condition
import mc.xalitoria.menu.paper.condition.Conditions
import mc.xalitoria.menu.api.model.Gate

private val GATE_OPTIONS: Set<String>  = ["requirements", "minimum_requirements", "stop_at_success", "success_commands", "deny_commands"]
private val CHECK_OPTIONS: Set<String> = ["type", "optional", "success_commands", "deny_commands"]

/**
 * Turns a DeluxeMenus requirement block (what `open_requirement`, `view_requirement` and the click requirements hold)
 * into a [Gate], or null if [node] holds no requirements. A requirement of a type XaliMenu can't run becomes
 * [Conditions.Never] with a warning, so the menu fails closed.
 */
internal fun Context.parseGate(node: YamlNode?): Gate? {
    val block = node.map() ?: return null
    block.keys.filter { it !in GATE_OPTIONS }.forEach { warn("requirement option '$it' is ignored") }

    val checks = block.option("requirements").map()?.entries.orEmpty().mapNotNull { (key, value) ->
        val name = key.content
        val body = value.map()
        if (body == null) {
            warn("requirement '$name' is not a section, so it is skipped")
            return@mapNotNull null
        }
        at("requirement '$name'") { parseCheck(body) }
    }
    if (checks.isEmpty()) return null

    return Gate(
        require = checks,
        minimum = block.option("minimum_requirements").int(),
        stopAtSuccess = block.option("stop_at_success").bool() ?: false,
        pass = actions(block.option("success_commands")),
        deny = actions(block.option("deny_commands")),
    )
}

internal fun Context.actions(node: YamlNode?): List<Action> =
    node.texts().mapNotNull { parseAction(it, ::warn) }

private fun Context.parseCheck(body: YamlMap): Check? {
    val type = body.option("type").text()?.trim()
    if (type == null) {
        warn("it has no type, so it is skipped")
        return null
    }
    val used = mutableSetOf<String>()
    fun take(key: String): String? = body.option(key).text()?.also { used += key }

    val negate = type.startsWith("!") && type != "!="
    val base = type.removePrefix("!").lowercase().replace(Regex("[ _]+"), " ").trim()

    val condition: Condition = when (base) {
        "has permission", "haspermission" -> take("permission")?.let(Conditions::Permission)
        "has money", "hasmoney" -> take("amount")?.let { Conditions.Money(it) }
        "has item", "hasitem" -> take("material")?.let {
            val data = take("data")?.toIntOrNull()
            Conditions.Item(
                material = material(it, data),
                amount = take("amount")?.toIntOrNull() ?: 1,
                name = take("name"),
                lore = body.option("lore").texts().also { if (it.isNotEmpty()) used += "lore" },
            )
        }
        "string equals", "stringequals" -> text(::take, EQUALS)
        "string equals ignorecase", "stringequalsignorecase" -> text(::take, EQUALS_IGNORE_CASE)
        "string contains", "stringcontains" -> text(::take, CONTAINS)
        "regex matches" -> take("input")?.let { input -> take("regex")?.let { Conditions.Regex(input, it) } }
        ">" -> compare(::take, GT)
        ">=" -> compare(::take, GTE)
        "==" -> compare(::take, EQ)
        "!=" -> compare(::take, NEQ)
        "<=" -> compare(::take, LTE)
        "<" -> compare(::take, LT)
        else -> {
            warn("the type '$type' isn't supported, so it will never pass")
            return Check(Conditions.Never, deny = actions(body.option("deny_commands")))
        }
    } ?: run {
        warn("it is missing an option of its type '$type', so it will never pass")
        Conditions.Never
    }

    (body.keys - CHECK_OPTIONS - used).forEach { warn("option '$it' is ignored") }

    return Check(
        condition = if (negate && condition != Conditions.Never) Conditions.Not(condition) else condition,
        optional = body.option("optional").bool() ?: false,
        pass = actions(body.option("success_commands")),
        deny = actions(body.option("deny_commands")),
    )
}

private fun text(take: (String) -> String?, op: Conditions.Text.Op): Conditions.Text? {
    val input = take("input") ?: return null
    val output = take("output") ?: return null
    return Conditions.Text(input, op, output)
}

private fun compare(take: (String) -> String?, op: Conditions.Compare.Op): Conditions.Compare? {
    val input = take("input") ?: return null
    val output = take("output") ?: return null
    return Conditions.Compare(input, op, output)
}
