// Draws a menu item as the game's inventory does: finds its item definition and model in the resource pack (then in the game's
// own files), bakes the quads, and rasterizes them with the `gui` display transform and the game's lights (see raster.js).

import { Assets, split } from './assets.js'
import { Model, loadModel, bakeElements, bakeLayers } from './bake.js'
import { chooseModels } from './itemdef.js'
import { renderItem, addGlint } from './raster.js'
import { specialQuads } from './special.js'

/** The size of the pixel of the game on the page: items are drawn 16 of these wide. */
export const SCALE = 3

/**
 * What the renderer needs to know about a menu item, taken from its keys.
 * @param {object} item the item as the menu file writes it (`material`, `item-model`, `model`, `color`, `damage`, `amount`...)
 * @param {{material?: string, itemModel?: string, customModelData?: number[]}} [known] what a plugin says a custom item is
 */
/** The address of the skin in a `texture:` value (base64 of the profile's JSON), or null. */
export function skinUrl(material) {
  const text = String(material ?? '')
  if (!text.startsWith('texture:')) return null
  try {
    const json = JSON.parse(atob(text.slice(8)))
    return json?.textures?.SKIN?.url?.replace(/^http:/, 'https:') ?? null
  } catch {
    return null
  }
}

export function stateOf(item, known = {}) {
  const color = typeof item.color === 'string' && /^#[0-9a-f]{6}$/i.test(item.color) ? parseInt(item.color.slice(1), 16) : null
  const floats = typeof item.model === 'number' ? [item.model] : known.customModelData
  const text = String(item.material ?? '')
  const head = text.startsWith('head:') || text.startsWith('texture:')
  return {
    material: head ? 'player_head' : (known.material ?? text.replace(/^minecraft:/, '')),
    skin: skinUrl(text),
    glint: item.glow === true || (item.glow !== false && (Object.keys(item.enchants ?? {}).length > 0 || Object.keys(item['stored-enchants'] ?? {}).length > 0)),
    itemModel: item['item-model'] ?? known.itemModel ?? null,
    state: {
      customModelData: floats ? { floats } : undefined,
      damage: item.damage ?? 0,
      maxDamage: known.maxDamage ?? 0,
      count: item.amount ?? 1,
      dyedColor: color,
      components: [],
    },
  }
}

export class ItemRenderer {
  /** @param {Assets} assets */
  constructor(assets) {
    this.assets = assets
    this.cache = new Map()
  }

  /**
   * The icon of the item as pixels, or null if nothing could be drawn.
   * @returns {Promise<{width: number, height: number, data: Uint8ClampedArray}|null>}
   */
  render(item, known = {}, scale = SCALE) {
    const described = stateOf(item, known)
    const key = JSON.stringify([described, scale])
    if (!this.cache.has(key)) this.cache.set(key, this.draw(described, scale).catch(() => null))
    return this.cache.get(key)
  }

  async draw({ material, itemModel, state, glint, skin }, scale) {
    const entries = await this.entries(material, itemModel, state)
    const layers = []
    const needed = new Set()
    for (const entry of entries) {
      const layer = await this.layer(entry, skin)
      if (!layer) continue
      layers.push(layer)
      for (const quad of layer.quads) if (quad.texture) needed.add(quad.texture)
    }
    if (layers.length === 0) return null
    const textures = new Map()
    await Promise.all([...needed].map(async (key) => {
      if (key.startsWith('url:')) { textures.set(key, await this.assets.fromUrl(key.slice(4))); return }
      const { ns, path } = split(key)
      textures.set(key, await this.assets.texture(ns, path))
    }))
    // A skin that cannot be read (the host does not allow other sites to) is drawn as the default one.
    for (const layer of layers) for (const quad of layer.quads) if (quad.texture?.startsWith('url:') && !textures.get(quad.texture)) quad.texture = 'minecraft:entity/player/wide/steve'
    if (layers.some((l) => l.quads.some((q) => q.texture === 'minecraft:entity/player/wide/steve')) && !textures.has('minecraft:entity/player/wide/steve')) {
      textures.set('minecraft:entity/player/wide/steve', await this.assets.texture('minecraft', 'entity/player/wide/steve'))
    }
    const image = renderItem(layers, (key) => textures.get(key) ?? null, scale)
    if (glint) {
      const sheen = await this.assets.texture('minecraft', 'misc/enchanted_glint_item')
      if (sheen) addGlint(image, sheen)
    }
    return image
  }

  /** The models the item definition picks; an item without a definition is drawn with the model of the same name. */
  async entries(material, itemModel, state) {
    const { ns, path } = split(itemModel ?? material)
    const definition = await this.assets.json(ns, 'items', path)
    if (definition) {
      // Tints by climate read the colormaps, which are fetched only for the items that ask.
      const text = JSON.stringify(definition)
      const colormaps = {}
      if (text.includes('minecraft:grass')) colormaps.grass = await this.assets.texture('minecraft', 'colormap/grass')
      if (text.includes('minecraft:foliage')) colormaps.foliage = await this.assets.texture('minecraft', 'colormap/foliage')
      return chooseModels(definition, { ...state, colormaps })
    }
    // No definition: the item with this name has a model of the same name, as an item or as a block.
    const asItem = await this.assets.json(ns, 'models', `item/${path}`)
    return [{ model: asItem ? `${ns}:item/${path}` : `${ns}:block/${path}`, tints: [] }]
  }

  /** The quads of one entry, placed by the display transform of its model. */
  async layer(entry, skin = null) {
    const key = entry.model ?? entry.base
    if (!key) return null
    const model = await loadModel(this.assets, key)
    if (!model) return null
    if (entry.special) {
      const built = specialQuads(entry.special, skin ? `url:${skin}` : null)
      if (!built) return model.elements ? this.plain(model, entry, key) : null
      return { quads: built.quads, display: model.display.gui, guiLight: model.guiLight ?? 'side', tints: [], alpha: undefined }
    }
    return this.plain(model, entry, key)
  }

  plain(model, entry, key) {
    let quads = []
    if (model.builtin === 'generated') quads = bakeLayers(model)
    else if (model.builtin === 'entity') return null
    else quads = bakeElements(model)
    if (quads.length === 0) return null
    return { quads, display: model.display.gui, guiLight: model.guiLight ?? 'side', tints: entry.tints, alpha: translucent(key) ? 'translucent' : undefined }
  }
}

/** Blocks the game draws in its translucent layer: glass, ice, slime and honey. Everything else with elements is solid or cutout. */
function translucent(modelKey) {
  const name = modelKey.replace(/^minecraft:/, '').replace(/^block\//, '')
  return /(^|_)glass(_pane)?($|_)|^ice$|^frosted_ice$|slime_block|honey_block|tinted_glass/.test(name) && !name.includes('bottle')
}

export { Assets, Model }
