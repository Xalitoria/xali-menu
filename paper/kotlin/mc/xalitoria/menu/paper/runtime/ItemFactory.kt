package mc.xalitoria.menu.paper.runtime

import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.BannerPatternLayers
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.DyedItemColor
import io.papermc.paper.datacomponent.item.Equippable
import io.papermc.paper.datacomponent.item.FoodProperties
import io.papermc.paper.datacomponent.item.Tool
import io.papermc.paper.datacomponent.item.TooltipDisplay
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import io.papermc.paper.registry.set.RegistrySet
import io.papermc.paper.registry.tag.TagKey
import mc.xalitoria.menu.paper.XaliMenu
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.util.TriState
import net.xalitoria.menu.model.AttributeOperation
import net.xalitoria.menu.model.Item
import net.xalitoria.menu.model.Trim
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.DyeColor
import org.bukkit.FireworkEffect
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.AttributeModifier
import org.bukkit.block.banner.Pattern
import org.bukkit.entity.TropicalFish
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemRarity
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.BannerMeta
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.FireworkEffectMeta
import org.bukkit.inventory.meta.FireworkMeta
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.inventory.meta.TropicalFishBucketMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionType
import java.util.concurrent.ConcurrentHashMap

/** Builds the stack an [Item] shows as. Anything that cannot be resolved is skipped (and noted by [validate]) rather than failing the menu. */
class ItemFactory(private val plugin: XaliMenu) {
    /**
     * The stack for [item] as [ctx]'s player sees it. [loaded] is called later if part of it (a player's skin) had to be
     * fetched, so the caller can draw the slot again.
     */
    fun build(item: Item, ctx: Context, loaded: () -> Unit = {}): ItemStack = try {
        construct(item, ctx, loaded)
    } catch (e: RuntimeException) {
        // One item that cannot be made must not take the whole menu down with it; it shows as a barrier, and is noted once.
        if (failed.add(e.toString())) plugin.logger.warning("Item '${item.material}' in menu '${ctx.menu}' could not be built: ${e.message}")
        ItemStack(Material.BARRIER)
    }

    /** The patterns of the item's `banner` that are known: a dye color and a pattern of the game. */
    private fun bannerPatterns(item: Item): List<Pattern> = item.banner.mapNotNull { layer ->
        val color = runCatching { DyeColor.valueOf(layer.color.uppercase()) }.getOrNull()
        val type = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN).get(Key.key(layer.pattern))
        if (color != null && type != null) Pattern(color, type) else null
    }

    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** Clears one-time item resolution warnings (for example after ItemsAdder finishes async data loading). */
    fun clearWarnings() = failed.clear()

    /**
     * Says once why an item is shown as a barrier because [spec] names nothing: a key that is not an item of the game, or an item that its source (Nexo, ItemsAdder...) does not
     * have, with the one it was probably meant to be. A head is not said: it is loading, which is not a mistake.
     */
    private fun noItem(item: Item, spec: String, ctx: Context) {
        val prefix = spec.substringBefore(':', "")
        if (prefix == "head" || prefix == "texture" || spec.isBlank() || !failed.add("no item: $spec")) return
        val source = plugin.registry.sources[prefix]
        val [what, candidates] = if (source != null) {
            "an item of $prefix" to ((source as? mc.xalitoria.menu.paper.hook.ListsItems)?.let { runCatching { it.ids() }.getOrNull() }.orEmpty())
        } else {
            "an item of the game" to RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM).map { it.key().value() }
        }
        val word = if (source != null) spec.substringAfter(':') else spec.substringAfter(':').lowercase()
        plugin.logger.warning("Item '${item.material}' of menu '${ctx.menu}' isn't $what${mc.xalitoria.menu.paper.format.Suggest.hint(word, candidates)}, so it is shown as a barrier")
    }

    private fun construct(item: Item, ctx: Context, loaded: () -> Unit): ItemStack {
        val spec = ctx.parse(item.material)
        var stack = base(spec, loaded) ?: run {
            noItem(item, spec, ctx)
            ItemStack(Material.BARRIER)
        }

        val amount = item.amount.resolve(ctx::parse) ?: 1
        stack.amount = amount.coerceIn(1, 99)

        stack.editMeta { meta ->
            item.name?.let { meta.displayName(ctx.component(it).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)) }
            if (item.lore.isNotEmpty()) meta.lore(item.lore.map { line ->
                ctx.component(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)
            })
            if (item.unbreakable.resolve(ctx::parse) == true) meta.isUnbreakable = true
            for ((key, value) in item.enchants) enchantment(key)?.let { meta.addEnchant(it, value, true) }
            if (meta is BannerMeta) for (pattern in bannerPatterns(item)) meta.addPattern(pattern)
            item.itemName?.let { meta.itemName(ctx.component(it)) }
            item.damage?.resolve(ctx::parse)?.let { if (meta is Damageable) meta.damage = it.coerceAtLeast(0) }
            item.rarity?.let { meta.setRarity(ItemRarity.valueOf(it.name)) }
            item.glow?.resolve(ctx::parse)?.let { meta.setEnchantmentGlintOverride(it) }
            item.maxStackSize?.resolve(ctx::parse)?.let { meta.setMaxStackSize(it.coerceIn(1, 99)) }
            item.itemModel?.let { key -> namespaced(key)?.let(meta::setItemModel) }
            item.tooltipStyle?.let { key -> namespaced(key)?.let(meta::setTooltipStyle) }
            item.attributes.forEachIndexed { index, spec ->
                val attribute = attribute(spec.attribute)
                val group = slotGroup(spec.slot)
                if (attribute != null && group != null) {
                    meta.addAttributeModifier(attribute, AttributeModifier(NamespacedKey(plugin, "modifier-$index"), spec.amount, operation(spec.operation), group))
                }
            }
            if (meta is EnchantmentStorageMeta) for ((key, value) in item.storedEnchants) enchantment(key)?.let { meta.addStoredEnchant(it, value, true) }
            if (meta is ArmorMeta) item.trim?.let { trim -> armorTrim(trim)?.let(meta::setTrim) }
            if (meta is BookMeta) item.book?.let { book ->
                meta.title(ctx.component(book.title))
                meta.author(ctx.component(book.author))
                meta.addPages(*book.pages.map(ctx::component).toTypedArray())
            }
            if (meta is FireworkMeta) item.firework?.let { firework ->
                meta.power = firework.power.coerceIn(0, 127)
                firework.effects.mapNotNull(::burst).forEach(meta::addEffect)
            }
            if (meta is FireworkEffectMeta) item.firework?.effects?.firstNotNullOfOrNull(::burst)?.let(meta::setEffect)
            if (meta is TropicalFishBucketMeta) item.fish?.let { fish ->
                runCatching { TropicalFish.Pattern.valueOf(fish.pattern.uppercase()) }.getOrNull()?.let(meta::setPattern)
                runCatching { DyeColor.valueOf(fish.bodyColor.uppercase()) }.getOrNull()?.let(meta::setBodyColor)
                runCatching { DyeColor.valueOf(fish.patternColor.uppercase()) }.getOrNull()?.let(meta::setPatternColor)
            }
            if (meta is PotionMeta) item.potionType?.let { type -> potionType(type)?.let(meta::setBasePotionType) }
            if (meta is PotionMeta) for (effect in item.potion) {
                RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(Key.key(effect.type))?.let {
                    meta.addCustomEffect(PotionEffect(it, effect.duration, effect.amplifier), true)
                }
            }
        }

        item.model?.resolve(ctx::parse)?.let {
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(it.toFloat()).build())
        }
        // A shield keeps its patterns and the color of its base in components, not in the meta a banner has.
        if (stack.type == Material.SHIELD) {
            val patterns = bannerPatterns(item)
            if (patterns.isNotEmpty()) stack.setData(DataComponentTypes.BANNER_PATTERNS, BannerPatternLayers.bannerPatternLayers(patterns))
            item.baseColor?.let { name -> runCatching { DyeColor.valueOf(name.uppercase()) }.getOrNull()?.let { stack.setData(DataComponentTypes.BASE_COLOR, it) } }
        }
        item.color?.let { hex -> parseColor(hex)?.let { stack.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(it)) } }
        val hideTooltip = item.hideTooltip.resolve(ctx::parse) == true
        if (item.hide.isNotEmpty() || hideTooltip) {
            val hidden = item.hide.mapNotNull(::componentType).toTypedArray()
            stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(hideTooltip).addHiddenComponents(*hidden).build())
        }
        item.equippable?.let { spec ->
            val slot = equipmentSlot(spec.slot) ?: return@let
            val builder = Equippable.equippable(slot).swappable(spec.swappable).dispensable(spec.dispensable).damageOnHurt(spec.damageOnHurt)
            spec.model?.let { key -> namespaced(key)?.let { builder.assetId(Key.key(it.namespace, it.key)) } }
            spec.cameraOverlay?.let { key -> namespaced(key)?.let { builder.cameraOverlay(Key.key(it.namespace, it.key)) } }
            stack.setData(DataComponentTypes.EQUIPPABLE, builder.build())
        }
        item.tool?.let { spec ->
            val rules = spec.rules.mapNotNull { rule ->
                val blocks = blocks(rule.blocks)
                if (blocks.isEmpty()) null
                else Tool.rule(RegistrySet.keySet(RegistryKey.BLOCK, blocks), rule.speed, rule.drops?.let(TriState::byBoolean) ?: TriState.NOT_SET)
            }
            stack.setData(
                DataComponentTypes.TOOL,
                Tool.tool().defaultMiningSpeed(spec.miningSpeed).damagePerBlock(spec.damagePerBlock.coerceAtLeast(0))
                    .canDestroyBlocksInCreative(spec.canDestroyInCreative).addRules(rules).build(),
            )
        }
        item.food?.let { stack.setData(DataComponentTypes.FOOD, FoodProperties.food().nutrition(it.nutrition.coerceAtLeast(0)).saturation(it.saturation).canAlwaysEat(it.alwaysEdible).build()) }
        item.skull?.let { skull ->
            val resolved = ctx.parse(skull).trim()
            if (resolved.isNotEmpty() && stack.type == Material.PLAYER_HEAD) plugin.heads.apply(stack, resolved, loaded)
        }
        // `nbt` accepts either a `{...}` custom_data SNBT compound or bracketed data components such as `[minecraft:profile={name:"Steve"}]`.
        @Suppress("DEPRECATION")
        item.nbt?.trim()?.takeIf { it.isNotEmpty() }?.let { snbt ->
            val spec = if (snbt.startsWith("[") && snbt.endsWith("]")) snbt else "[custom_data=$snbt]"
            stack = Bukkit.getUnsafe().modifyItemStack(stack, spec)
        }
        for (entry in item.properties) {
            try {
                entry.value.apply(stack, ctx)
            } catch (e: RuntimeException) {
                if (failed.add("property:${entry.key}:$e")) {
                    plugin.logger.warning("Item property '${entry.key}' on '${item.material}' in menu '${ctx.menu}' failed: ${e.message}")
                }
            }
        }
        return stack
    }

    /** What is wrong with [item] that does not depend on who looks at it; nothing means it will build. */
    fun validate(item: Item): List<String> {
        val problems = mutableListOf<String>()
        if (!hasPlaceholder(item.material) && base(item.material, {}, probe = true) == null) problems += "material '${item.material}' isn't a known item"
        item.enchants.keys.filter { enchantment(it) == null }.forEach { problems += "enchantment '$it' doesn't exist" }
        item.hide.filter { componentType(it) == null }.forEach { problems += "'$it' isn't a data component to hide" }
        item.color?.let { if (parseColor(it) == null) problems += "color '$it' isn't #rrggbb" }
        item.storedEnchants.keys.filter { enchantment(it) == null }.forEach { problems += "stored enchantment '$it' doesn't exist" }
        item.itemModel?.let { if (namespaced(it) == null) problems += "item-model '$it' isn't a key" }
        item.potionType?.let { if (potionType(it) == null) problems += "potion-type '$it' doesn't exist" }
        item.trim?.let { if (armorTrim(it) == null) problems += "trim ${it.pattern} / ${it.material} doesn't exist" }
        item.tooltipStyle?.let { if (namespaced(it) == null) problems += "tooltip-style '$it' isn't a key" }
        item.attributes.forEach { spec ->
            if (attribute(spec.attribute) == null) problems += "attribute '${spec.attribute}' doesn't exist"
            if (slotGroup(spec.slot) == null) problems += "attribute slot '${spec.slot}' doesn't exist"
        }
        item.equippable?.let { spec ->
            if (equipmentSlot(spec.slot) == null) problems += "equippable slot '${spec.slot}' doesn't exist"
            listOfNotNull(spec.model, spec.cameraOverlay).filter { namespaced(it) == null }.forEach { problems += "equippable key '$it' isn't a key" }
        }
        item.tool?.let { spec ->
            spec.rules.forEach { rule ->
                if (rule.blocks.isEmpty()) problems += "a tool rule needs blocks"
                rule.blocks.filter { entry -> blocks(listOf(entry)).isEmpty() }.forEach { problems += "tool rule: '$it' isn't a block or a tag of blocks" }
            }
        }
        // Numbers written in a file are limited by `@Within` on the model, which the file check tells with a path and a line; here only what a file cannot show.
        for (entry in listOf("amount" to item.amount, "model" to item.model, "damage" to item.damage, "max-stack-size" to item.maxStackSize)) {
            val value = entry.second
            if (value != null && value.fixed == null && !hasPlaceholder(value.text)) problems += "${entry.first} '${value.text}' isn't a number"
        }
        item.firework?.let { firework ->
            firework.effects.flatMap { it.colors + it.fade }.filter { parseColor(it) == null }.forEach { problems += "firework color '$it' isn't #rrggbb" }
            firework.effects.filter { it.colors.isEmpty() }.forEach { _ -> problems += "a firework effect needs a color" }
        }
        item.fish?.let { fish ->
            if (runCatching { TropicalFish.Pattern.valueOf(fish.pattern.uppercase()) }.isFailure) problems += "fish pattern '${fish.pattern}' doesn't exist"
            listOf(fish.bodyColor, fish.patternColor).filter { runCatching { DyeColor.valueOf(it.uppercase()) }.isFailure }.forEach { problems += "fish color '$it' doesn't exist" }
        }
        for (entry in item.properties) {
            val property = entry.value as? net.xalitoria.menu.ValidatedItemProperty ?: continue
            val found = try {
                property.validate()
            } catch (e: RuntimeException) {
                listOf("validation failed: ${e.message ?: e::class.java.simpleName}")
            }
            found.forEach { problems += "${entry.key}: $it" }
        }
        return problems
    }

    /** The stack for a material written as `<prefix>:<id>` or a plain item key; null if it is not one. */
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

        private fun namespaced(key: String): NamespacedKey? = NamespacedKey.fromString(key.trim().lowercase())

        /** The blocks for keys such as `stone` and tags such as `#minecraft:mineable/pickaxe`; what cannot be found is left out. */
        private fun blocks(specs: List<String>): List<TypedKey<org.bukkit.block.BlockType>> {
            val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BLOCK)
            return specs.flatMap { spec ->
                val key = namespaced(spec.removePrefix("#")) ?: return@flatMap emptyList()
                if (spec.startsWith("#")) runCatching { registry.getTag(TagKey.create(RegistryKey.BLOCK, key)).toList() }.getOrDefault(emptyList())
                else if (registry.get(key) != null) listOf(TypedKey.create(RegistryKey.BLOCK, key)) else emptyList()
            }
        }

        private fun attribute(key: String) = namespaced(key)?.let { RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE).get(it) }

        private fun slotGroup(name: String): EquipmentSlotGroup? = EquipmentSlotGroup.getByName(name.trim().lowercase())

        private fun equipmentSlot(name: String): EquipmentSlot? = runCatching { EquipmentSlot.valueOf(name.trim().uppercase()) }.getOrNull()

        private fun operation(operation: AttributeOperation) = when (operation) {
            AttributeOperation.ADD -> AttributeModifier.Operation.ADD_NUMBER
            AttributeOperation.MULTIPLY_BASE -> AttributeModifier.Operation.ADD_SCALAR
            AttributeOperation.MULTIPLY_TOTAL -> AttributeModifier.Operation.MULTIPLY_SCALAR_1
        }

        private fun potionType(key: String): PotionType? {
            val id = namespaced(key) ?: return null
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.POTION).get(id)
        }

        private fun armorTrim(trim: Trim): ArmorTrim? {
            val registry = RegistryAccess.registryAccess()
            val pattern = namespaced(trim.pattern)?.let { registry.getRegistry(RegistryKey.TRIM_PATTERN).get(it) } ?: return null
            val material = namespaced(trim.material)?.let { registry.getRegistry(RegistryKey.TRIM_MATERIAL).get(it) } ?: return null
            return ArmorTrim(material, pattern)
        }

        /** The burst for [effect], or null if it has no color that can be read. */
        private fun burst(effect: net.xalitoria.menu.model.FireworkEffect): FireworkEffect? {
            val colors = effect.colors.mapNotNull(::parseColor)
            if (colors.isEmpty()) return null
            return FireworkEffect.builder()
                .with(FireworkEffect.Type.valueOf(effect.type.name))
                .withColor(colors)
                .withFade(effect.fade.mapNotNull(::parseColor))
                .flicker(effect.flicker)
                .trail(effect.trail)
                .build()
        }

        private fun componentType(key: String): DataComponentType? =
            RegistryAccess.registryAccess().getRegistry(RegistryKey.DATA_COMPONENT_TYPE).get(Key.key(key))

        private fun parseColor(hex: String): Color? {
            val value = hex.removePrefix("#")
            if (value.length != 6) return null
            return value.toIntOrNull(16)?.let { Color.fromRGB(it) }
        }
    }
}
