// Chooses the models an item is drawn with, the way the game reads assets/<namespace>/items/<name>.json: a tree of choices
// (`condition`, `select`, `range_dispatch`) that ends in models. What the choices ask of the item is in [ItemState].
// The result is a flat list, one entry per model that is drawn, each with the colors for its tint indexes.

import { split } from './assets.js'

/**
 * What an item looks like to the choices of its definition.
 * @typedef {Object} ItemState
 * @property {{floats?: number[], strings?: string[], flags?: boolean[], colors?: number[]}} [customModelData]
 * @property {number} [damage] points of durability used
 * @property {number} [maxDamage] 0 for an item that does not wear out
 * @property {number} [count]
 * @property {number} [maxStack]
 * @property {number|null} [dyedColor] the `dyed_color` component, as 0xRRGGBB
 * @property {number|null} [potionColor] the color of the potion's effects, as 0xRRGGBB
 * @property {string[]} [components] the data components the item has
 * @property {{grass?: object, foliage?: object}} [colormaps] the pixels of the colormaps, for tints by climate
 */

/** @typedef {{model?: string, tints: (number|null)[], special?: object, base?: string, transformation?: {left_rotation?: number[], right_rotation?: number[], scale?: number[], translation?: number[]}}} Entry */

const WHITE = 0xffffff

/** The name without `minecraft:`, which the definitions use both ways. */
function bare(name) {
  return typeof name === 'string' ? name.replace(/^minecraft:/, '') : ''
}

/** An int that the game stores as signed ARGB, as 0xRRGGBB. */
function rgb(value) {
  return typeof value === 'number' ? value & 0xffffff : null
}

/** The color a tint gives to its layer; [state] has the dye and potion of the item, and the default of the tint is used when it has none. */
export function tintColor(tint, state) {
  const fallback = rgb(tint.default) ?? WHITE
  switch (bare(tint.type)) {
    case 'constant': return Array.isArray(tint.value) ? floats(tint.value) : (rgb(tint.value) ?? WHITE)
    case 'dye': return state.dyedColor ?? fallback
    case 'potion': return state.potionColor ?? fallback
    case 'custom_model_data': return state.customModelData?.colors?.[tint.index ?? 0] ?? fallback
    case 'grass': return colormap(state.colormaps?.grass, tint.temperature ?? 0.5, tint.downfall ?? 1) ?? rgb(tint.default) ?? 0x91bd59
    case 'foliage': return colormap(state.colormaps?.foliage, tint.temperature ?? 0.5, tint.downfall ?? 1) ?? rgb(tint.default) ?? 0x77ab2f
    default: return fallback
  }
}

/** The color the game reads from a 256 by 256 colormap for a climate: temperature across, and downfall (scaled by temperature) down. */
export function colormap(texture, temperature, downfall) {
  if (!texture) return null
  const x = Math.floor((1 - temperature) * 255)
  const y = Math.floor((1 - downfall * temperature) * 255)
  const i = (Math.min(texture.height - 1, y * texture.height / 256 | 0) * texture.width + Math.min(texture.width - 1, x * texture.width / 256 | 0)) * 4
  return (texture.data[i] << 16) | (texture.data[i + 1] << 8) | texture.data[i + 2]
}

function floats([r, g, b]) {
  const byte = (v) => Math.max(0, Math.min(255, Math.round(v * 255)))
  return (byte(r) << 16) | (byte(g) << 8) | byte(b)
}

/**
 * The models for an item definition.
 * @param {any} definition the parsed items/<name>.json
 * @param {ItemState} state
 * @returns {Entry[]}
 */
export function chooseModels(definition, state) {
  const out = []
  walk(definition?.model ?? definition, state, out, 0)
  return out
}

function walk(node, state, out, depth) {
  if (!node || typeof node !== 'object' || depth > 32) return
  switch (bare(node.type ?? 'model')) {
    case 'model':
      if (typeof node.model === 'string') out.push({ model: node.model, tints: (node.tints ?? []).map((t) => tintColor(t, state)), ...(node.transformation ? { transformation: node.transformation } : {}) })
      break
    case 'composite': for (const m of node.models ?? []) walk(m, state, out, depth + 1); break
    case 'condition': walk(condition(node, state) ? node.on_true : node.on_false, state, out, depth + 1); break
    case 'select': walk(select(node, state), state, out, depth + 1); break
    case 'range_dispatch': walk(range(node, state), state, out, depth + 1); break
    case 'special': out.push({ special: node.model, base: node.base, tints: [] }); break
    default: break // empty, bundle/selected_item and the rest show nothing here
  }
}

function condition(node, state) {
  switch (bare(node.property)) {
    case 'custom_model_data': return Boolean(state.customModelData?.flags?.[node.index ?? 0])
    case 'damaged': return (state.maxDamage ?? 0) > 0 && (state.damage ?? 0) > 0
    case 'broken': return (state.maxDamage ?? 0) > 0 && (state.damage ?? 0) >= (state.maxDamage ?? 0) - 1
    case 'has_component': return (state.components ?? []).map(bare).includes(bare(node.component))
    default: return false // using_item, selected, carried, extended_view and the rest depend on a player
  }
}

/** The branch of a `select` for the item in the inventory. */
function select(node, state) {
  let value = null
  switch (bare(node.property)) {
    case 'display_context': value = 'gui'; break
    case 'custom_model_data': value = state.customModelData?.strings?.[node.index ?? 0] ?? null; break
    case 'main_hand': value = 'right'; break
    default: value = null
  }
  if (value !== null) {
    for (const entry of node.cases ?? []) {
      const wanted = Array.isArray(entry.when) ? entry.when : [entry.when]
      if (wanted.map((w) => (typeof w === 'string' ? bare(w) : w)).includes(bare(value))) return entry.model
    }
  }
  return node.fallback
}

/** The branch of a `range_dispatch`: the entry with the largest threshold that the value reaches, else the fallback. */
function range(node, state) {
  const value = rangeValue(node, state)
  if (value !== null) {
    const entry = [...(node.entries ?? [])].filter((e) => e.threshold <= value).sort((a, b) => b.threshold - a.threshold)[0]
    if (entry) return entry.model
  }
  return node.fallback
}

function rangeValue(node, state) {
  const scale = node.scale ?? 1
  switch (bare(node.property)) {
    case 'custom_model_data': {
      const value = state.customModelData?.floats?.[node.index ?? 0]
      return value === undefined ? null : value * scale
    }
    case 'damage': {
      const max = state.maxDamage ?? 0
      const damage = state.damage ?? 0
      return (node.normalize ?? true) ? (max > 0 ? damage / max : 0) * scale : damage * scale
    }
    case 'count': {
      const max = state.maxStack ?? 64
      return (node.normalize ?? true) ? ((state.count ?? 1) / max) * scale : (state.count ?? 1) * scale
    }
    case 'cooldown': case 'use_duration': case 'time': case 'compass': case 'crossbow/pull': case 'bundle/fullness': case 'use_cycle':
      return 0
    default: return null
  }
}

/** The key of the definition an item uses: its `item_model` component, otherwise the item itself. */
export function definitionKey(material, itemModel) {
  const { ns, path } = split(itemModel ?? material)
  return { ns, path }
}
