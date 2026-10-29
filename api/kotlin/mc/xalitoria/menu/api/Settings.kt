package mc.xalitoria.menu.api

/**
 * The keys of an action or condition as they are written in a menu file, next to its `type`, for the [ActionType] or
 * [ConditionType] that builds it. A value of the wrong kind reads as absent, the way a missing one does; the numbers
 * and booleans may be written as text (`points = "3"`).
 *
 * Its methods have the plain names in Kotlin (`int("points", 1)`) and bean names in Java (`getInt("points", 1)`).
 * Nothing in them is a Kotlin type, so a plugin that carries its own copy of the Kotlin runtime can call them.
 */
class Settings(val values: Map<String, Any?>) {
    /** Whether [key] is written. */
    fun has(key: String): Boolean = values[key] != null

    @JvmName("getString")
    fun string(key: String): String? = when (val value = values[key]) {
        null, is Map<*, *>, is List<*> -> null
        else -> value.toString()
    }

    @JvmName("getString")
    fun string(key: String, fallback: String): String = string(key) ?: fallback

    /** The text at [key]; an [IllegalArgumentException] naming the key if there is none, which the menu's loading reports. */
    fun requireString(key: String): String = string(key) ?: throw IllegalArgumentException("'$key' is missing")

    @JvmName("getInt")
    fun int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    @JvmName("getInt")
    fun int(key: String, fallback: Int): Int = int(key) ?: fallback

    @JvmName("getLong")
    fun long(key: String): Long? = when (val value = values[key]) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    @JvmName("getLong")
    fun long(key: String, fallback: Long): Long = long(key) ?: fallback

    @JvmName("getDouble")
    fun double(key: String): Double? = when (val value = values[key]) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    @JvmName("getDouble")
    fun double(key: String, fallback: Double): Double = double(key) ?: fallback

    @JvmName("getBoolean")
    fun boolean(key: String): Boolean? = when (val value = values[key]) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    @JvmName("getBoolean")
    fun boolean(key: String, fallback: Boolean): Boolean = boolean(key) ?: fallback

    /** The texts at [key]: a list's, or a single text as a list of one; empty if there are none. */
    @JvmName("getStrings")
    fun strings(key: String): List<String> = when (val value = values[key]) {
        null, is Map<*, *> -> emptyList()
        is List<*> -> value.filter { it != null && it !is Map<*, *> && it !is List<*> }.map { it.toString() }
        else -> listOf(value.toString())
    }

    /** The object at [key], such as `reward { points = 3 }`. */
    @JvmName("getSection")
    fun section(key: String): Settings? = (values[key] as? Map<*, *>)?.let(::of)

    /** The objects of the list at [key]. */
    @JvmName("getSections")
    fun sections(key: String): List<Settings> = (values[key] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::of) }

    private fun of(map: Map<*, *>) = Settings(map.entries.associate { it.key.toString() to it.value })
}
