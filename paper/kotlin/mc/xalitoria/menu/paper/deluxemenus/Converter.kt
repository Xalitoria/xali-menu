package mc.xalitoria.menu.paper.deluxemenus

import com.charleskorn.kaml.YamlMap
import kotlin.time.Duration.Companion.seconds
import mc.xalitoria.menu.api.model.*
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.TagStringIO

private val MENU_OPTIONS = [
    "menu_title", "size", "inventory_type", "open_command", "register_command", "args", "args_usage_message",
    "update_interval", "open_requirement", "open_commands", "close_commands", "items",
]

/** The kinds of click an item can handle, with the prefix of their DeluxeMenus options (`left_click_commands`). */
private enum class ClickKind(val prefix: String) {
    ANY(""), LEFT("left_"), RIGHT("right_"), SHIFT_LEFT("shift_left_"), SHIFT_RIGHT("shift_right_"), MIDDLE("middle_");

    val commands get() = "${prefix}click_commands"
    val requirement get() = "${prefix}click_requirement"
}

private fun Clicks.with(kind: ClickKind, handler: Handler): Clicks = when (kind) {
    ANY -> copy(any = handler)
    LEFT -> copy(left = handler)
    RIGHT -> copy(right = handler)
    SHIFT_LEFT -> copy(shiftLeft = handler)
    SHIFT_RIGHT -> copy(shiftRight = handler)
    MIDDLE -> copy(middle = handler)
}

/** The tooltip parts each DeluxeMenus `hide_*` option and `item_flags` entry hides, by data component key. */
private val HIDES = mapOf(
    "hide_attributes" to "attribute_modifiers",
    "hide_enchantments" to "enchantments",
    "hide_effects" to "potion_contents",
    "hide_potion_effects" to "potion_contents",
    "hide_unbreakable" to "unbreakable",
)
private val FLAGS = mapOf(
    "hide_attributes" to "attribute_modifiers",
    "hide_enchants" to "enchantments",
    "hide_potion_effects" to "potion_contents",
    "hide_unbreakable" to "unbreakable",
    "hide_destroys" to "can_break",
    "hide_placed_on" to "can_place_on",
    "hide_dye" to "dyed_color",
)

private val ITEM_OPTIONS = [
    "material", "data", "slot", "slots", "priority", "update", "display_name", "name", "lore", "amount",
    "dynamic_amount", "model_data", "rgb", "enchantments", "banner_meta", "potion_effects", "unbreakable",
    "item_flags", "entity_type", "entitytype", "view_requirement",
] + HIDES.keys

private val CLICK_OPTIONS = ClickKind.entries.flatMap { [it.commands, it.requirement] }

private class NbtOption(val option: String, val type: String)

private val NBT = [
    NbtOption("nbt_string", "string"), NbtOption("nbt_int", "int"), NbtOption("nbt_byte", "byte"),
    NbtOption("nbt_short", "short"), NbtOption("nbt_long", "long"), NbtOption("nbt_float", "float"),
    NbtOption("nbt_double", "double"),
]
private val NBT_OPTIONS = NBT.flatMap { [it.option, it.option + "s"] }

/** Converts a DeluxeMenus menu section into a [Menu], noting what couldn't be carried over in [Context.warnings]. */
internal fun Context.convertMenu(section: YamlMap): Menu {
    (section.keys - MENU_OPTIONS).forEach { warn("option '$it' isn't supported and is ignored") }

    val size = section.option("size").int() ?: 54.also { warn("it has no size, so it gets 54 slots") }
    val items = section.option("items").map()?.entries.orEmpty().mapNotNull { (key, value) ->
        val body = value.map()
        if (body == null) {
            warn("item '${key.content}' is not a section, so it is skipped")
            return@mapNotNull null
        }
        at("item '${key.content}'") { convertItem(body) }?.let { key.content to it }
    }.toMap()

    return Menu(
        title = section.option("menu_title").text() ?: "".also { warn("it has no menu_title") },
        size = size,
        type = section.option("inventory_type").text()?.lowercase() ?: "chest",
        commands = section.option("open_command").texts().map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() },
        register = section.option("register_command").bool() ?: true,
        args = section.option("args").texts(),
        argsUsage = section.option("args_usage_message").text(),
        refresh = section.option("update_interval").int()?.seconds,
        open = at("open_requirement") { parseGate(section.option("open_requirement")) },
        onOpen = actions(section.option("open_commands")),
        onClose = actions(section.option("close_commands")),
        items = items,
    )
}

private fun Context.convertItem(item: YamlMap): Item? {
    val raw = item.option("material").text()
    if (raw == null) {
        warn("it has no material, so it is skipped")
        return null
    }
    (item.keys - ITEM_OPTIONS - CLICK_OPTIONS - NBT_OPTIONS).forEach { warn("option '$it' isn't supported and is ignored") }

    val data = item.option("data").int()
    var material = material(raw, data)
    (item.option("entity_type").text() ?: item.option("entitytype").text())?.let { entity ->
        if (material.endsWith("egg")) material = "${entity.lowercase()}_spawn_egg"
        else warn("entity_type only applies to spawn eggs")
    }

    val hide = LinkedHashSet<String>()
    for ((key, value) in HIDES) if (item.option(key).bool() == true) hide += value
    item.option("item_flags").texts().forEach { flag ->
        FLAGS[flag.lowercase()]?.let(hide::add) ?: warn("item flag '$flag' isn't supported")
    }

    val slots = item.option("slots").texts().flatMap { entry ->
        val range = entry.split('-').mapNotNull { it.trim().toIntOrNull() }
        when {
            entry.contains('-') && range.size == 2 -> (range[0]..range[1]).toList()
            else -> listOfNotNull(entry.trim().toIntOrNull()).also { if (it.isEmpty()) warn("slot '$entry' isn't a number") }
        }
    }
    val slot = item.option("slot").int()
    if (slot == null && slots.isEmpty()) warn("it has no slot, so it never shows")

    return Item(
        material = material,
        slot = slot,
        slots = slots,
        priority = item.option("priority").int() ?: 0,
        amount = item.option("amount").int() ?: 1,
        dynamicAmount = item.option("dynamic_amount").text(),
        name = item.option("display_name").text() ?: item.option("name").text(),
        lore = item.option("lore").texts(),
        enchants = enchants(item),
        hide = hide.toList(),
        unbreakable = item.option("unbreakable").bool() ?: false,
        model = item.option("model_data").int(),
        color = item.option("rgb").text()?.let(::color).also { if (item.option("rgb").text() != null && it == null) warn("rgb isn't three numbers") },
        banner = banner(item),
        potion = potions(item),
        nbt = nbt(item),
        update = item.option("update").bool() ?: false,
        view = at("view_requirement") { parseGate(item.option("view_requirement")) },
        click = clicks(item),
    )
}

private fun Context.clicks(item: YamlMap): Clicks = ClickKind.entries.fold(Clicks()) { clicks, kind ->
    val commands = actions(item.option(kind.commands))
    val require = at(kind.requirement) { parseGate(item.option(kind.requirement)) }
    if (commands.isEmpty() && require == null) clicks else clicks.with(kind, Handler(commands, require))
}

private fun Context.enchants(item: YamlMap): Map<String, Int> = item.option("enchantments").texts().mapNotNull { entry ->
    val [name, level] = entry.split(';', ':').map(String::trim).let { it.getOrNull(0) to it.getOrNull(1) }
    val key = name?.lowercase()?.let { ENCHANTMENTS[it] ?: it }
    val parsed = level?.toIntOrNull() ?: if (level == null) 1 else null
    if (key.isNullOrEmpty() || parsed == null) {
        warn("enchantment '$entry' isn't NAME;LEVEL, so it is skipped")
        null
    } else key to parsed
}.toMap()

private fun Context.banner(item: YamlMap): List<BannerLayer> = item.option("banner_meta").texts().mapNotNull { entry ->
    val parts = entry.split(';', ':').map { it.trim().lowercase() }
    if (parts.size != 2 || parts.any { it.isEmpty() }) {
        warn("banner pattern '$entry' isn't COLOR;PATTERN, so it is skipped")
        null
    } else BannerLayer(parts[0], parts[1])
}

private fun Context.potions(item: YamlMap): List<PotionEffect> = item.option("potion_effects").texts().mapNotNull { entry ->
    val parts = entry.split(';', ':').map { it.trim() }
    val duration = parts.getOrNull(1)?.toIntOrNull()
    if (parts[0].isEmpty() || duration == null) {
        warn("potion effect '$entry' isn't TYPE;DURATION;AMPLIFIER, so it is skipped")
        null
    } else {
        val type = parts[0].lowercase()
        PotionEffect(POTION_EFFECTS[type] ?: type, duration, parts.getOrNull(2)?.toIntOrNull() ?: 0)
    }
}

private fun color(rgb: String): String? {
    val parts = rgb.split(',', ';', ' ').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() }
    if (parts.size != 3 || parts.any { it == null || it !in 0..255 }) return null
    return "#" + parts.joinToString("") { "%02x".format(it) }
}

/** The `nbt_*` options as one SNBT compound, with the keys DeluxeMenus gives as `key:value`. */
private fun Context.nbt(item: YamlMap): String? {
    val tag = CompoundBinaryTag.builder()
    var any = false
    for ((option, type) in NBT) {
        (item.option(option).texts() + item.option(option + "s").texts()).forEach { entry ->
            val key = entry.substringBefore(':').trim()
            val value = entry.substringAfter(':', "").trim()
            val ok = key.isNotEmpty() && entry.contains(':') && when (type) {
                "string" -> { tag.putString(key, value); true }
                "int" -> value.toIntOrNull()?.also { tag.putInt(key, it) } != null
                "byte" -> value.toByteOrNull()?.also { tag.putByte(key, it) } != null
                "short" -> value.toShortOrNull()?.also { tag.putShort(key, it) } != null
                "long" -> value.toLongOrNull()?.also { tag.putLong(key, it) } != null
                "float" -> value.toFloatOrNull()?.also { tag.putFloat(key, it) } != null
                else -> value.toDoubleOrNull()?.also { tag.putDouble(key, it) } != null
            }
            if (ok) any = true else warn("$option '$entry' isn't key:value of that type, so it is skipped")
        }
    }
    return if (any) TagStringIO.tagStringIO().asString(tag.build()) else null
}
