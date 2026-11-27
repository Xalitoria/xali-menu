// Material-aware item meta filtering and grouping for the web editor's Safe / Unsafe mode.
// Pure data logic: never mutates the item; toggling Unsafe mode off hides inapplicable fields
// while reporting any already-set keys so they are preserved untouched on save.

import { parts } from './icons.js'

const DYEABLE = new Set([
  'leather_helmet', 'leather_chestplate', 'leather_leggings', 'leather_boots',
  'leather_horse_armor', 'wolf_armor',
])

const POTIONS = new Set([
  'potion', 'splash_potion', 'lingering_potion', 'tipped_arrow',
])

const BOOKS = new Set([
  'written_book', 'writable_book',
])

const FIREWORKS = new Set([
  'firework_rocket', 'firework_star',
])

const DAMAGEABLE_EXTRA = new Set([
  'turtle_helmet', 'wolf_armor', 'shield', 'bow', 'crossbow', 'trident',
  'fishing_rod', 'flint_and_steel', 'shears', 'elytra',
  'carrot_on_a_stick', 'warped_fungus_on_a_stick', 'brush', 'mace',
])

const DAMAGEABLE_SUFFIX = /_(?:sword|pickaxe|axe|shovel|hoe|spear|helmet|chestplate|leggings|boots)$/
const ARMOR_SUFFIX = /_(?:helmet|chestplate|leggings|boots)$/
const HEAD_PREFIXES = new Set(['head', 'texture', 'basehead', 'hdb'])

/** Keys that only apply to specific item types in Safe mode. Everything else is universal. */
export const MATERIAL_SPECIFIC_KEYS = new Set([
  'skull', 'color', 'banner', 'base-color',
  'potion', 'potion-type', 'stored-enchants',
  'trim', 'book', 'firework', 'fish',
  'damage', 'unbreakable',
])

/**
 * Resolves [material] (vanilla, `head:...`, or a server-resolved custom item like `itemsadder:ns:id` / `nexo:id`)
 * to its underlying vanilla item key and durability metadata.
 *
 * @param {string|undefined} material
 * @param {Map<string, {material?: string, maxDamage?: number}>|null} [resolved]
 * @returns {{ baseMaterial: string|null, source: string, maxDamage: number }}
 */
export function resolveItemBase(material, resolved = null) {
  const raw = String(material ?? '').trim()
  if (!raw || raw.includes('%') || raw.includes('{')) {
    return { baseMaterial: null, source: 'dynamic', maxDamage: 0 }
  }
  const { prefix, id } = parts(raw)
  const info = resolved?.get?.(raw) ?? null
  if (prefix) {
    if (HEAD_PREFIXES.has(prefix)) {
      return { baseMaterial: 'player_head', source: prefix, maxDamage: 0 }
    }
    if (info?.material) {
      return { baseMaterial: String(info.material).toLowerCase(), source: prefix, maxDamage: info.maxDamage ?? 0 }
    }
    return { baseMaterial: null, source: prefix, maxDamage: 0 }
  }
  return { baseMaterial: id, source: 'vanilla', maxDamage: info?.maxDamage ?? 0 }
}

/** Whether [baseMaterial] is an item with durability. */
export function isDamageable(baseMaterial, maxDamage = 0) {
  if (maxDamage > 0) return true
  if (!baseMaterial) return false
  return DAMAGEABLE_EXTRA.has(baseMaterial) || DAMAGEABLE_SUFFIX.test(baseMaterial)
}

/** Whether [baseMaterial] is wearable armor that accepts trims (`ArmorMeta`). */
export function isArmor(baseMaterial) {
  if (!baseMaterial) return false
  return baseMaterial === 'turtle_helmet' || ARMOR_SUFFIX.test(baseMaterial)
}

/** Whether property [key] is applicable to an item with [base] (`resolveItemBase`) in Safe mode. */
export function isKeyApplicable(key, base) {
  if (!MATERIAL_SPECIFIC_KEYS.has(key)) return true
  const mat = base.baseMaterial
  if (!mat) return false
  switch (key) {
    case 'skull': return mat === 'player_head' && !HEAD_PREFIXES.has(base.source)
    case 'color': return DYEABLE.has(mat)
    case 'banner': return mat.endsWith('_banner') || mat === 'shield'
    case 'base-color': return mat === 'shield'
    case 'potion':
    case 'potion-type': return POTIONS.has(mat)
    case 'stored-enchants': return mat === 'enchanted_book'
    case 'trim': return isArmor(mat)
    case 'book': return BOOKS.has(mat)
    case 'firework': return FIREWORKS.has(mat)
    case 'fish': return mat === 'tropical_fish_bucket'
    case 'damage':
    case 'unbreakable': return isDamageable(mat, base.maxDamage)
    default: return true
  }
}

/**
 * Evaluates which schema keys are shown, hidden, recommended as quick-add chips, or preserved-but-hidden
 * for [item]. Never mutates [item].
 *
 * @param {object} item
 * @param {string[]} schemaKeys
 * @param {{ unsafe?: boolean, resolved?: Map<string, object>|null, alwaysHidden?: string[] }} [options]
 */
export function evaluateItemMeta(item, schemaKeys, { unsafe = false, resolved = null, alwaysHidden = [] } = {}) {
  const base = resolveItemBase(item?.material, resolved)
  const blocked = new Set(alwaysHidden)
  const allowed = new Set()
  const hidden = []
  const hiddenPresent = []
  const recommended = []

  for (const key of schemaKeys) {
    if (blocked.has(key)) {
      hidden.push(key)
      continue
    }
    const ok = unsafe || isKeyApplicable(key, base)
    if (ok) {
      allowed.add(key)
      if (MATERIAL_SPECIFIC_KEYS.has(key) && isKeyApplicable(key, base) && !(key in (item ?? {}))) {
        recommended.push(key)
      }
    } else {
      hidden.push(key)
      if (item && key in item) hiddenPresent.push(key)
    }
  }

  return {
    base,
    allowed,
    hidden,
    hiddenPresent,
    recommended,
  }
}

/** Category label for grouping item properties in the `+ Add property` dropdown. */
const KEY_GROUPS = {
  // Display & Text
  name: 'Display & Text',
  'item-name': 'Display & Text',
  lore: 'Display & Text',
  rarity: 'Display & Text',
  glow: 'Display & Text',
  hide: 'Display & Text',
  'hide-tooltip': 'Display & Text',
  'tooltip-style': 'Display & Text',
  // Item Meta
  skull: 'Item Meta',
  color: 'Item Meta',
  trim: 'Item Meta',
  banner: 'Item Meta',
  'base-color': 'Item Meta',
  'potion-type': 'Item Meta',
  potion: 'Item Meta',
  'stored-enchants': 'Item Meta',
  book: 'Item Meta',
  firework: 'Item Meta',
  fish: 'Item Meta',
  damage: 'Item Meta',
  unbreakable: 'Item Meta',
  enchants: 'Item Meta',
  // Models & Components
  model: 'Models & Components',
  'item-model': 'Models & Components',
  'max-stack-size': 'Models & Components',
  attributes: 'Models & Components',
  food: 'Models & Components',
  equippable: 'Models & Components',
  tool: 'Models & Components',
  nbt: 'Models & Components',
  // Placement & Interaction
  material: 'Placement & Interaction',
  amount: 'Placement & Interaction',
  slot: 'Placement & Interaction',
  slots: 'Placement & Interaction',
  'inventory-slots': 'Placement & Interaction',
  priority: 'Placement & Interaction',
  update: 'Placement & Interaction',
  view: 'Placement & Interaction',
  click: 'Placement & Interaction',
  accept: 'Placement & Interaction',
  // Bedrock
  bedrock: 'Bedrock',
}

export const GROUP_ORDER = [
  'Item Meta',
  'Display & Text',
  'Placement & Interaction',
  'Models & Components',
  'Bedrock',
  'Extensions',
  'Other',
]

export function itemPropertyGroup(key) {
  if (key.includes(':')) return 'Extensions'
  return KEY_GROUPS[key] ?? 'Other'
}
