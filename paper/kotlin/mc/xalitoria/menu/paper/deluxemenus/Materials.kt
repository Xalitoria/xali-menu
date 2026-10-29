package mc.xalitoria.menu.paper.deluxemenus

/**
 * What the importer needs to know about items before 1.13, which DeluxeMenus configs of that age use: numeric ids
 * (`278`), data values (`wool` with data `14`) and names that have since changed. The server knows the answers; this
 * keeps the importer itself free of the server API.
 */
interface Materials {
    /** The key of the item that numeric [id] with [data] became, such as `diamond_pickaxe`, or null if there is none. */
    fun legacy(id: Int, data: Int): String?

    /** The key of the item that the old [name] with [data] became, or null if [name] was never an old item. */
    fun legacy(name: String, data: Int): String?

    /** Whether [name] is an item of today. */
    fun exists(name: String): Boolean

    /** Knows nothing, so every material is kept as written. */
    object None : Materials {
        override fun legacy(id: Int, data: Int): String? = null
        override fun legacy(name: String, data: Int): String? = null
        override fun exists(name: String): Boolean = true
    }
}

/** Enchantment names as the Bukkit API used to spell them, and the keys they have now. */
internal val ENCHANTMENTS = mapOf(
    "arrow_damage" to "power",
    "arrow_fire" to "flame",
    "arrow_infinite" to "infinity",
    "arrow_knockback" to "punch",
    "damage_all" to "sharpness",
    "damage_arthropods" to "bane_of_arthropods",
    "damage_undead" to "smite",
    "dig_speed" to "efficiency",
    "durability" to "unbreaking",
    "loot_bonus_blocks" to "fortune",
    "loot_bonus_mobs" to "looting",
    "luck" to "luck_of_the_sea",
    "oxygen" to "respiration",
    "protection_environmental" to "protection",
    "protection_explosions" to "blast_protection",
    "protection_fall" to "feather_falling",
    "protection_fire" to "fire_protection",
    "protection_projectile" to "projectile_protection",
    "sweeping" to "sweeping_edge",
    "water_worker" to "aqua_affinity",
)

/** Potion effect names as the Bukkit API used to spell them, and the keys they have now. */
internal val POTION_EFFECTS = mapOf(
    "confusion" to "nausea",
    "damage_resistance" to "resistance",
    "fast_digging" to "haste",
    "harm" to "instant_damage",
    "heal" to "instant_health",
    "increase_damage" to "strength",
    "jump" to "jump_boost",
    "slow" to "slowness",
    "slow_digging" to "mining_fatigue",
)
