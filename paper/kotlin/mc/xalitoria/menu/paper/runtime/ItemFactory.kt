package mc.xalitoria.menu.paper.runtime

import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.DyedItemColor
import io.papermc.paper.datacomponent.item.TooltipDisplay
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import mc.xalitoria.menu.paper.XaliMenu
import mc.xalitoria.menu.api.model.Item
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.DyeColor
import org.bukkit.Material
import org.bukkit.block.banner.Pattern
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BannerMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect

/** Builds the stack an [Item] shows as. Anything that can't be resolved is skipped (and noted by [validate]) rather than failing the menu. */
class ItemFactory(private val plugin: XaliMenu) {
    /**
     * The stack for [item] as [ctx]'s player sees it. [loaded] is called later if part of it (a player's skin) had to be
     * fetched, so the caller can draw the slot again.
     */
    fun build(item: Item, ctx: Context, loaded: () -> Unit = {}): ItemStack {
        val spec = ctx.parse(item.material)
        var stack = base(spec, loaded) ?: ItemStack(Material.BARRIER)

        val amount = item.dynamicAmount?.let { ctx.parse(it).trim().toDoubleOrNull()?.toInt() } ?: item.amount
        stack.amount = amount.coerceIn(1, stack.maxStackSize.coerceAtLeast(1))

        stack.editMeta { meta ->
            item.name?.let { meta.displayName(ctx.component(it).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)) }
            if (item.lore.isNotEmpty()) meta.lore(item.lore.map { line ->
                ctx.component(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            })
            if (item.unbreakable) meta.isUnbreakable = true
            for ((key, value) in item.enchants) enchantment(key)?.let { meta.addEnchant(it, value, true) }
            if (meta is BannerMeta) for (layer in item.banner) {
                val color = runCatching { DyeColor.valueOf(layer.color.uppercase()) }.getOrNull()
                val type = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN).get(Key.key(layer.pattern))
                if (color != null && type != null) meta.addPattern(Pattern(color, type))
            }
            if (meta is PotionMeta) for (effect in item.potion) {
                RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(Key.key(effect.type))?.let {
                    meta.addCustomEffect(PotionEffect(it, effect.duration, effect.amplifier), true)
                }
            }
        }

        item.model?.let {
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(it.toFloat()).build())
        }
        item.color?.let { hex -> parseColor(hex)?.let { stack.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(it)) } }
        if (item.hide.isNotEmpty()) {
            val hidden = item.hide.mapNotNull(::componentType).toTypedArray()
            stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(*hidden).build())
        }
        // `nbt` is raw SNBT; UnsafeValues is the only API that applies it.
        @Suppress("DEPRECATION")
        item.nbt?.let { snbt -> stack = Bukkit.getUnsafe().modifyItemStack(stack, "[custom_data=$snbt]") }
        return stack
    }

    /** What is wrong with [item] that doesn't depend on who looks at it; nothing means it will build. */
    fun validate(item: Item): List<String> {
        val problems = mutableListOf<String>()
        if ('%' !in item.material && base(item.material, {}, probe = true) == null) problems += "material '${item.material}' isn't a known item"
        item.enchants.keys.filter { enchantment(it) == null }.forEach { problems += "enchantment '$it' doesn't exist" }
        item.hide.filter { componentType(it) == null }.forEach { problems += "'$it' isn't a data component to hide" }
        item.color?.let { if (parseColor(it) == null) problems += "color '$it' isn't #rrggbb" }
        return problems
    }

    /** The stack for a material written as `<prefix>:<id>` or a plain item key; null if it isn't one. */
    private fun base(spec: String, loaded: () -> Unit, probe: Boolean = false): ItemStack? {
        val prefix = spec.substringBefore(':', "")
        return when {
            prefix == "head" || prefix == "texture" ->
                if (probe) ItemStack(Material.PLAYER_HEAD) else plugin.heads.head(spec, loaded)
            prefix in plugin.registry.sources -> if (probe) ItemStack(Material.BARRIER) else runCatching { plugin.registry.sources.getValue(prefix).item(spec.substringAfter(':')) }.getOrNull()?.clone()
            else -> material(spec)?.let { ItemStack(it) }
        }
    }

    companion object {
        /** The item for a vanilla key such as `diamond_sword` or `minecraft:diamond_sword`. */
        fun material(spec: String): Material? =
            Material.matchMaterial(spec.trim())?.takeIf { it.isItem && !it.isLegacy }

        fun enchantment(key: String) = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(Key.key(key))

        private fun componentType(key: String): DataComponentType? =
            RegistryAccess.registryAccess().getRegistry(RegistryKey.DATA_COMPONENT_TYPE).get(Key.key(key))

        private fun parseColor(hex: String): Color? {
            val value = hex.removePrefix("#")
            if (value.length != 6) return null
            return value.toIntOrNull(16)?.let { Color.fromRGB(it) }
        }
    }
}
