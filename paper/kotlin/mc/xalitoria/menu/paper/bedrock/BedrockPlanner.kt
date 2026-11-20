package mc.xalitoria.menu.paper.bedrock

import mc.xalitoria.menu.paper.config.MainConfig
import net.xalitoria.menu.model.BedrockMenu
import net.xalitoria.menu.model.Handler
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Menu

/** How a menu should be presented to a specific viewer. */
sealed interface Presentation {
    /** Standard Bukkit/Paper inventory window. */
    data object Inventory : Presentation

    /** Redirect to another menu by name. */
    data class Redirect(val menu: String) : Presentation

    /** Bedrock native SimpleForm (explicit buttons, or auto-converted from menu items and pages). */
    data object SimpleForm : Presentation

    /** Bedrock native ModalForm (two buttons: confirm and cancel). */
    data object ModalForm : Presentation

    /** Bedrock native CustomForm (inputs, toggles, dropdowns, sliders, labels). */
    data object CustomForm : Presentation
}

/** One clickable action choice exposed for a menu item on Bedrock. */
data class ClickChoice(val kind: Kind, val label: String, val handler: Handler) {
    enum class Kind { ANY, LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, MIDDLE }
}

/** Pure rules for choosing Bedrock presentation, titles, overlays, icons and click adaptations. */
object BedrockPlanner {
    /**
     * Decides how [menu] opens for a player with [profile], given the server's [defaults].
     * Always returns [Presentation.Inventory] for Java clients.
     */
    fun plan(menu: Menu, profile: ClientProfile, defaults: MainConfig.Bedrock): Presentation {
        if (!profile.isBedrock) return Presentation.Inventory
        val cfg = menu.bedrock
        if (cfg != null) {
            val filtered = cfg.devices.isNotEmpty() || cfg.inputs.isNotEmpty() || cfg.ui.isNotEmpty()
            val matches = profile.matches(devices = cfg.devices, inputs = cfg.inputs, ui = cfg.ui)
            if (!matches) {
                return if (filtered) Presentation.Inventory else defaultForm(profile, defaults)
            }
            if (!cfg.menu.isNullOrBlank()) return Presentation.Redirect(cfg.menu!!)
            return when (cfg.mode) {
                BedrockMenu.Mode.INVENTORY -> Presentation.Inventory
                BedrockMenu.Mode.SIMPLE -> Presentation.SimpleForm
                BedrockMenu.Mode.MODAL -> Presentation.ModalForm
                BedrockMenu.Mode.CUSTOM -> Presentation.CustomForm
                BedrockMenu.Mode.AUTO -> when {
                    cfg.fields.isNotEmpty() -> Presentation.CustomForm
                    cfg.confirm != null || cfg.cancel != null -> Presentation.ModalForm
                    cfg.buttons.isNotEmpty() -> Presentation.SimpleForm
                    else -> defaultForm(profile, defaults)
                }
            }
        }
        return defaultForm(profile, defaults)
    }

    private fun defaultForm(profile: ClientProfile, defaults: MainConfig.Bedrock): Presentation = when (defaults.defaultMode) {
        MainConfig.DefaultBedrockMode.INVENTORY -> Presentation.Inventory
        MainConfig.DefaultBedrockMode.TOUCH_FORM -> if (profile.prefersForm) Presentation.SimpleForm else Presentation.Inventory
        MainConfig.DefaultBedrockMode.ALWAYS_FORM -> Presentation.SimpleForm
    }

    /** Raw title template to use for [menu] when opened by [profile]. */
    fun titleTemplate(menu: Menu, profile: ClientProfile): String =
        if (profile.isBedrock && !menu.bedrock?.title.isNullOrBlank()) menu.bedrock!!.title!! else menu.title

    /** Whether `inventory-slots` packet overlays should be sent to a viewer with [profile]. */
    fun shouldDrawInventoryOverlay(menu: Menu, profile: ClientProfile, defaults: MainConfig.Bedrock): Boolean {
        if (!profile.isBedrock || !profile.isPocket) return true
        return menu.bedrock?.inventoryOverlay ?: defaults.pocketInventoryOverlay
    }

    /** Whether [item] should be skipped as a decorative filler when auto-converting a menu into a `SimpleForm`. */
    fun isFiller(item: Item): Boolean {
        if (item.bedrock?.hidden == true) return true
        if (ClickChoices.hasAny(item)) return false
        val name = item.name?.trim().orEmpty()
        return name.isEmpty() && item.lore.isEmpty() && item.bedrock?.text.isNullOrBlank()
    }

    /**
     * Collects the distinct click handlers of [item] that a Bedrock player can trigger, with human-readable labels.
     */
    fun choicesFor(item: Item): List<ClickChoice> = ClickChoices.choicesFor(item)

    /**
     * Builds the display label for an auto-converted button from [name], optional [firstLore] and optional [override].
     */
    fun buttonLabel(override: String?, name: String?, material: String, firstLore: String?, includeLore: Boolean): String {
        if (!override.isNullOrBlank()) return override
        val title = name?.takeIf { it.isNotBlank() } ?: humanMaterial(material)
        return if (includeLore && !firstLore.isNullOrBlank()) "$title\n§r§7$firstLore" else title
    }

    /** Turns `diamond_sword` or `nexo:ruby_blade` into `Diamond Sword` or `Ruby Blade`. */
    fun humanMaterial(spec: String): String {
        val base = spec.substringAfter(':').trim()
        if (base.isEmpty()) return "Item"
        return base.split('_').filter { it.isNotEmpty() }.joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercaseChar() }
        }
    }
}

/** Resolves available click handlers and Bedrock action-picker labels for an [Item]. */
object ClickChoices {
    fun hasAny(item: Item): Boolean {
        val c = item.click
        return c.any != null || c.left != null || c.right != null || c.shiftLeft != null || c.shiftRight != null || c.middle != null
    }

    /**
     * Distinct click choices of [item]. If only `any` is set, returns a single choice; if specific click types are set,
     * lists each specific handler with its Bedrock label (falling back to "Left click", "Right click", etc.).
     */
    fun choicesFor(item: Item): List<ClickChoice> {
        val c = item.click
        val labels = item.bedrock
        val specific = buildList {
            c.left?.let { add(ClickChoice(ClickChoice.Kind.LEFT, labels?.leftLabel ?: "Left click", it)) }
            c.right?.let { add(ClickChoice(ClickChoice.Kind.RIGHT, labels?.rightLabel ?: "Right click", it)) }
            c.shiftLeft?.let { add(ClickChoice(ClickChoice.Kind.SHIFT_LEFT, labels?.shiftLeftLabel ?: "Shift + Left click", it)) }
            c.shiftRight?.let { add(ClickChoice(ClickChoice.Kind.SHIFT_RIGHT, labels?.shiftRightLabel ?: "Shift + Right click", it)) }
            c.middle?.let { add(ClickChoice(ClickChoice.Kind.MIDDLE, labels?.middleLabel ?: "Middle click", it)) }
        }
        if (specific.isNotEmpty()) return specific
        return c.any?.let { listOf(ClickChoice(ClickChoice.Kind.ANY, "Select", it)) } ?: emptyList()
    }
}

/** Maps explicit overrides or vanilla item keys to Bedrock `FormImage` icons. */
object BedrockIcons {
    /** Special Bedrock texture paths whose filename differs from the Java item key. */
    private val SPECIAL_PATHS = mapOf(
        "clock" to "textures/items/clock_item",
        "compass" to "textures/items/compass_item",
        "recovery_compass" to "textures/items/recovery_compass_item",
        "written_book" to "textures/items/book_written",
        "writable_book" to "textures/items/book_writable",
        "enchanted_book" to "textures/items/book_enchanted",
        "book" to "textures/items/book_normal",
        "oak_sign" to "textures/items/sign",
        "chest" to "textures/blocks/chest_front",
        "ender_chest" to "textures/blocks/ender_chest_front",
        "crafting_table" to "textures/blocks/crafting_table_front",
        "furnace" to "textures/blocks/furnace_front_off",
        "barrier" to "textures/blocks/barrier",
        "grass_block" to "textures/blocks/grass_side_carried",
        "tnt" to "textures/blocks/tnt_side",
        "beacon" to "textures/blocks/beacon",
        "bookshelf" to "textures/blocks/bookshelf",
        "emerald_block" to "textures/blocks/emerald_block",
        "diamond_block" to "textures/blocks/diamond_block",
        "gold_block" to "textures/blocks/gold_block",
        "iron_block" to "textures/blocks/iron_block",
        "redstone_block" to "textures/blocks/redstone_block",
        "netherite_block" to "textures/blocks/netherite_block",
        "anvil" to "textures/blocks/anvil_top_damaged_0",
        "emerald" to "textures/items/emerald",
        "diamond" to "textures/items/diamond",
        "gold_ingot" to "textures/items/gold_ingot",
        "iron_ingot" to "textures/items/iron_ingot",
        "nether_star" to "textures/items/nether_star",
        "experience_bottle" to "textures/items/experience_bottle",
        "ender_pearl" to "textures/items/ender_pearl",
        "ender_eye" to "textures/items/ender_eye",
        "paper" to "textures/items/paper",
        "arrow" to "textures/items/arrow",
        "spectral_arrow" to "textures/items/arrow",
        "tipped_arrow" to "textures/items/arrow",
        "feather" to "textures/items/feather",
        "map" to "textures/items/map_empty",
        "filled_map" to "textures/items/map_filled",
        "firework_rocket" to "textures/items/fireworks",
        "totem_of_undying" to "textures/items/totem",
        "golden_apple" to "textures/items/apple_golden",
        "enchanted_golden_apple" to "textures/items/apple_golden",
        "player_head" to "textures/items/skull_steve",
    )

    private val BLOCK_SUFFIXES = listOf(
        "_block", "_planks", "_log", "_wood", "_wool", "_glass", "_stained_glass", "_glass_pane",
        "_ore", "_stone", "_bricks", "_concrete", "_terracotta", "_leaves", "_sand", "_dirt",
        "_stairs", "_slab", "_wall", "_carpet", "_shulker_box",
    )

    private val EXACT_BLOCKS = setOf(
        "stone", "cobblestone", "obsidian", "bedrock", "sand", "gravel", "dirt", "clay",
        "ice", "packed_ice", "blue_ice", "snow", "netherrack", "glowstone", "spawner", "hopper",
        "dispenser", "dropper", "observer", "piston", "sticky_piston", "jukebox", "note_block",
        "sponge", "wet_sponge", "barrel", "loom", "smoker", "blast_furnace", "smithing_table",
        "cartography_table", "fletching_table", "grindstone", "stonecutter", "lectern", "composter",
    )

    /** Resolves an optional [override] or item [material] to a Bedrock [FormIcon], or null when none applies. */
    fun resolve(material: String?, override: String?, iconUrlTemplate: String = ""): FormIcon? {
        val raw = override?.trim()?.takeIf { it.isNotEmpty() } ?: material?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ('%' in raw || '{' in raw) return null
        if (raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true)) {
            return FormIcon(FormIcon.Type.URL, raw)
        }
        if (raw.startsWith("textures/", ignoreCase = true)) {
            return FormIcon(FormIcon.Type.PATH, raw)
        }
        val colon = raw.indexOf(':')
        if (colon >= 0) {
            val prefix = raw.substring(0, colon).lowercase()
            if (prefix != "minecraft") return null
        }
        val key = raw.substringAfter(':').lowercase()
        if (key.isEmpty() || key == "air") return null
        if (iconUrlTemplate.isNotBlank()) {
            return FormIcon(FormIcon.Type.URL, iconUrlTemplate.replace("{material}", key))
        }
        SPECIAL_PATHS[key]?.let { return FormIcon(FormIcon.Type.PATH, it) }
        val isBlock = key in EXACT_BLOCKS || BLOCK_SUFFIXES.any { key.endsWith(it) }
        return FormIcon(FormIcon.Type.PATH, if (isBlock) "textures/blocks/$key" else "textures/items/$key")
    }
}
