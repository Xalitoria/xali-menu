// Finds the picture of an item in a resource pack, the way the game picks its model: the item definition in
// assets/<namespace>/items/<name>.json chooses a model (by custom model data, for instance), the model json names a texture
// (or a parent that does), and the texture is a png. Custom items of plugins such as Nexo and ItemsAdder are ordinary items
// with an item model or custom model data, so this covers them; the plugin tells the editor which, see Server.resolve.

import { key } from './fonts.js'

/** Splits `ns:path` into its parts; a bare path is in `minecraft`. */
export function split(text) {
  const [ns, path] = key(text).split(/:(.*)/s)
  return { ns, path }
}

/**
 * The model that an item definition [definition] picks for an item with [properties].
 * @param {any} definition the parsed items/<name>.json
 * @param {{customModelData?: number[]}} properties
 * @returns {string|null} the model key, or null if the definition shows none
 */
export function pickModel(definition, properties = {}) {
  const node = definition?.model ?? definition
  return walk(node, properties)
}

function walk(node, properties) {
  if (!node || typeof node !== 'object') return null
  switch (key(node.type ?? 'minecraft:model', 'minecraft')) {
    case 'minecraft:model': return typeof node.model === 'string' ? node.model : null
    case 'minecraft:range_dispatch': {
      if (node.property === 'minecraft:custom_model_data' || node.property === 'custom_model_data') {
        const value = properties.customModelData?.[node.index ?? 0]
        if (value !== undefined) {
          const entry = [...(node.entries ?? [])].filter((e) => e.threshold <= value).sort((a, b) => b.threshold - a.threshold)[0]
          if (entry) return walk(entry.model, properties)
        }
      }
      return walk(node.fallback, properties)
    }
    case 'minecraft:select': return walk(node.fallback ?? node.cases?.[0]?.model, properties)
    case 'minecraft:condition': return walk(node.on_false, properties) ?? walk(node.on_true, properties)
    case 'minecraft:composite': return (node.models ?? []).map((m) => walk(m, properties)).find(Boolean) ?? null
    default: return null
  }
}

/** The texture key that a model (with its parents) names first: `layer0`, or any texture. */
export async function modelTexture(readModel, modelKey, depth = 0) {
  if (depth > 8) return null
  const { ns, path } = split(modelKey)
  const model = await readModel(ns, path)
  if (!model) return null
  const textures = model.textures ?? {}
  const pick = textures.layer0 ?? textures.all ?? textures.particle ?? Object.values(textures)[0]
  if (typeof pick === 'string' && !pick.startsWith('#')) return pick
  if (typeof pick === 'string') {
    const target = textures[pick.slice(1)]
    if (typeof target === 'string' && !target.startsWith('#')) return target
  }
  return model.parent ? modelTexture(readModel, model.parent, depth + 1) : null
}
