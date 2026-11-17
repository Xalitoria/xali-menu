// The values that the game's registries hold, for the keys of a menu that take one of them (an enchantment, an effect, a dye color...): a dropdown
// of them instead of a box to type in. The names come from the game's language file, which the editor already loads for the tooltips, so the lists
// are those of the version of the assets in use; a value that is not among them (another plugin's, or a datapack's) can still be typed.

/** `[value, label]` pairs by registry name, filled by [loadRegistries]. */
export const registries = new Map()

/** The dye colors of the game, in the order of the palette of the game. */
const DYES = ['white', 'light_gray', 'gray', 'black', 'brown', 'red', 'orange', 'yellow', 'lime', 'green', 'cyan', 'light_blue', 'blue', 'purple', 'magenta', 'pink']

const words = (id) => id.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())

/** Lists that the game does not name in its language file. */
const FIXED = {
  'attribute-slot': ['any', 'mainhand', 'offhand', 'hand', 'head', 'chest', 'legs', 'feet', 'armor', 'body'].map((v) => [v, words(v)]),
  'equippable-slot': ['HEAD', 'CHEST', 'LEGS', 'FEET', 'BODY', 'OFF_HAND'].map((v) => [v, words(v.toLowerCase())]),
  'fish-pattern': ['KOB', 'SUNSTREAK', 'SNOOPER', 'DASHER', 'BRINELY', 'SPOTTY', 'FLOPPER', 'STRIPEY', 'GLITTER', 'BLOCKFISH', 'BETTY', 'CLAYFISH'].map((v) => [v, words(v.toLowerCase())]),
  'dye-upper': DYES.map((v) => [v.toUpperCase(), words(v)]),
  component: ['enchantments', 'stored_enchantments', 'attribute_modifiers', 'unbreakable', 'can_break', 'can_place_on', 'dyed_color', 'trim', 'jukebox_playable',
    'potion_contents', 'banner_patterns', 'firework_explosion', 'fireworks', 'written_book_content', 'tool'].map((v) => [v, words(v)]),
}

/** The ids that [lang] names with keys of the form `prefix.<id>`, as `[id, name]`, in the order of the file. */
function named(lang, prefix, suffix = '') {
  const out = []
  for (const [key, text] of Object.entries(lang)) {
    if (!key.startsWith(`${prefix}.`) || !key.endsWith(suffix) || typeof text !== 'string') continue
    const id = key.slice(prefix.length + 1, key.length - suffix.length)
    if (/^[a-z0-9_]+$/.test(id)) out.push([id, text])
  }
  return out
}

/** Sorts `[id, label]` pairs by label. */
const sorted = (list) => list.sort((a, b) => a[1].localeCompare(b[1]))

/** The registries as far as the language file [lang] tells them: `registry name -> [[value, label]]`. */
export function fromLang(lang) {
  const attribute = new Map()
  for (const [key, text] of Object.entries(lang)) {
    // Both `attribute.name.attack_damage` and the older `attribute.name.generic.attack_damage`.
    const match = /^attribute\.name\.(?:(?:generic|player)\.)?([a-z_]+)$/.exec(key)
    if (match && typeof text === 'string') attribute.set(match[1], text)
  }
  const colors = named(lang, 'color.minecraft')
  const patterns = [...new Set(named(lang, 'block.minecraft.banner', '.white').map(([id]) => id))].map((id) => [id, words(id)])
  const potions = named(lang, 'item.minecraft.potion.effect').map(([id, text]) => [id, text])
  return {
    enchantment: sorted(named(lang, 'enchantment.minecraft')),
    effect: sorted(named(lang, 'effect.minecraft')),
    attribute: sorted([...attribute]),
    color: colors.length ? colors : DYES.map((v) => [v, words(v)]),
    'banner-pattern': sorted(patterns),
    'potion-type': sorted(potions),
    'trim-pattern': sorted(named(lang, 'trim_pattern.minecraft')),
    'trim-material': sorted(named(lang, 'trim_material.minecraft')),
  }
}

/** Fills the registries from [lang] (the fixed ones are always there). */
export function loadRegistries(lang = {}) {
  registries.clear()
  for (const [name, list] of Object.entries({ ...FIXED, ...fromLang(lang) })) if (list.length) registries.set(name, list)
}

/** Which registry the key [key] of the type [owner] takes its values from. The keys are those of the schema. */
const KEYS = {
  'Item.potion-type': 'potion-type', 'Item.base-color': 'color', 'Item.hide': 'component', 'Item.enchants': 'enchantment', 'Item.stored-enchants': 'enchantment',
  'BannerLayer.color': 'color', 'BannerLayer.pattern': 'banner-pattern',
  'PotionEffect.type': 'effect',
  'AttributeModifier.attribute': 'attribute', 'AttributeModifier.slot': 'attribute-slot',
  'Equippable.slot': 'equippable-slot',
  'Trim.pattern': 'trim-pattern', 'Trim.material': 'trim-material',
  'Fish.pattern': 'fish-pattern', 'Fish.body-color': 'dye-upper', 'Fish.pattern-color': 'dye-upper',
}

/** The name of the registry that the field [field] (as the schema has it, with its `owner`) takes values from, or null. */
export function registryOf(field) {
  return (field?.owner && KEYS[`${field.owner}.${field.key}`]) || null
}

/** The `[value, label]` choices for [field], or null when it has no registry or the registry is empty. */
export function registryChoices(field) {
  const name = registryOf(field)
  return name ? registries.get(name) ?? null : null
}
