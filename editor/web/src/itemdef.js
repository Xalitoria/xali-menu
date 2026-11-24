// Chooses the models an item is drawn with:
//   - Modern (1.21.4+ / pack_format >= 46): reads `assets/<ns>/items/<name>.json` (`chooseModels`).
//   - Legacy (1.9 – 1.21.3 / pack_format < 46): evaluates `overrides` inside `assets/<ns>/models/item/<name>.json` (`matchLegacyOverrides`).

import { split } from './assets.js'

const WHITE = 0xffffff

function bare(name) {
  return typeof name === 'string' ? name.replace(/^minecraft:/, '') : ''
}

function rgb(value) {
  return typeof value === 'number' ? value & 0xffffff : null
}

export function tintColor(tint, state) {
  const fallback = rgb(tint.default) ?? WHITE
  switch (bare(tint.type)) {
    case 'constant': return Array.isArray(tint.value) ? floats(tint.value) : (rgb(tint.value) ?? WHITE)
    case 'dye': return state.dyedColor ?? fallback
    case 'potion': return state.potionColor ?? fallback
    case 'firework': return state.fireworkColor ?? fallback
    case 'custom_model_data': return state.customModelData?.colors?.[tint.index ?? 0] ?? fallback
    case 'grass': return colormap(state.colormaps?.grass, tint.temperature ?? 0.5, tint.downfall ?? 1) ?? rgb(tint.default) ?? 0x91bd59
    case 'foliage': return colormap(state.colormaps?.foliage, tint.temperature ?? 0.5, tint.downfall ?? 1) ?? rgb(tint.default) ?? 0x77ab2f
    default: return fallback
  }
}

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

export function chooseModels(definition, state) {
  const out = []
  walk(definition?.model ?? definition, state, out, 0)
  if (definition?.oversized_in_gui === true) {
    for (const entry of out) entry.oversizedInGui = true
  }
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
    default: break
  }
}

function condition(node, state) {
  switch (bare(node.property)) {
    case 'custom_model_data': return Boolean(state.customModelData?.flags?.[node.index ?? 0])
    case 'damaged': return (state.maxDamage ?? 0) > 0 && (state.damage ?? 0) > 0
    case 'broken': return (state.maxDamage ?? 0) > 0 && (state.damage ?? 0) >= (state.maxDamage ?? 0) - 1
    case 'has_component': return (state.components ?? []).map(bare).includes(bare(node.component))
    default: return false
  }
}

function select(node, state) {
  let value = null
  switch (bare(node.property)) {
    case 'display_context': value = 'gui'; break
    case 'custom_model_data': value = state.customModelData?.strings?.[node.index ?? 0] ?? null; break
    case 'main_hand': value = 'right'; break
    case 'trim_material': value = state.trimMaterial ?? null; break
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
      const count = state.count ?? 1
      return (node.normalize ?? true) ? (count / max) * scale : count * scale
    }
    case 'cooldown': case 'use_duration': case 'time': case 'compass': case 'crossbow/pull': case 'bundle/fullness': case 'use_cycle':
      return 0
    default: return null
  }
}

/**
 * Evaluates legacy (MC 1.9 – 1.21.3 / pack_format < 46) `overrides` inside a `models/item/<name>.json`.
 * In Minecraft, the LAST override whose every predicate key `k` satisfies `itemValue(k) >= threshold` wins.
 * Returns the winning model key (`ns:path`) or `null` if no override matches.
 */
export function matchLegacyOverrides(modelJson, state) {
  const overrides = modelJson?.overrides
  if (!Array.isArray(overrides) || overrides.length === 0) return null
  const cmd = state?.customModelData?.floats?.[0] ?? 0
  const maxDamage = state?.maxDamage ?? 0
  const damage = state?.damage ?? 0
  const values = {
    custom_model_data: cmd,
    damaged: maxDamage > 0 && damage > 0 ? 1 : 0,
    damage: maxDamage > 0 ? damage / maxDamage : 0,
    pull: 0,
    pulling: 0,
    blocking: 0,
    cast: 0,
    charged: 0,
    firework: 0,
    lefthanded: 0,
    time: 0,
    angle: 0,
    trim_type: state?.trimMaterial ? legacyTrimType(state.trimMaterial) : 0,
  }
  let matched = null
  for (const ov of overrides) {
    if (!ov?.predicate || typeof ov.model !== 'string') continue
    let ok = true
    for (const [rawKey, threshold] of Object.entries(ov.predicate)) {
      const key = bare(rawKey)
      const actual = values[key]
      if (actual === undefined || actual < Number(threshold)) {
        ok = false
        break
      }
    }
    if (ok) matched = ov.model
  }
  return matched
}

const LEGACY_TRIMS = {
  quartz: 0.1, iron: 0.2, netherite: 0.3, redstone: 0.4, copper: 0.5,
  gold: 0.6, emerald: 0.7, diamond: 0.8, lapis: 0.9, amethyst: 1.0,
}

function legacyTrimType(material) {
  return LEGACY_TRIMS[bare(material).toLowerCase()] ?? 0
}

export function definitionKey(material, itemModel) {
  const { ns, path } = split(itemModel ?? material)
  return { ns, path }
}
