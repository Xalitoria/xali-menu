// Draws a menu item as the game's inventory does: finds its item definition and model in the resource pack (then in the game's
// own files), bakes the quads, and rasterizes them with the `gui` display transform and the game's lights (see raster.js).

import { numberOf, flagOf } from './dynamic.js'
import { Assets, split } from './assets.js'
import { Model, loadModel, bakeElements, bakeLayers, transformQuads } from './bake.js'
import { chooseModels } from './itemdef.js'
import { renderItem, addGlint, downsample } from './raster.js'
import { specialQuads, bannerLayers, shieldLayers } from './special.js'

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

/** The color a firework star shows: the average of the colors of its first burst, as 0xRRGGBB, or null when it has none. */
export function fireworkColor(item) {
  const colors = (item.firework?.effects?.[0]?.colors ?? []).filter((c) => /^#[0-9a-f]{6}$/i.test(String(c))).map((c) => parseInt(c.slice(1), 16))
  if (colors.length === 0) return null
  const mean = (shift) => Math.trunc(colors.reduce((sum, c) => sum + ((c >> shift) & 255), 0) / colors.length)
  return (mean(16) << 16) | (mean(8) << 8) | mean(0)
}

export function stateOf(item, known = {}) {
  const color = typeof item.color === 'string' && /^#[0-9a-f]{6}$/i.test(item.color) ? parseInt(item.color.slice(1), 16) : null
  const model = numberOf(item.model, null)
  const floats = model !== null ? [model] : known.customModelData
  const text = String(item.material ?? '')
  const head = text.startsWith('head:') || text.startsWith('texture:')
  return {
    material: head ? 'player_head' : (known.material ?? text.replace(/^minecraft:/, '')),
    skin: skinUrl(text),
    banner: Array.isArray(item.banner) ? item.banner : [],
    baseColor: typeof item['base-color'] === 'string' ? item['base-color'] : null,
    glint: flagOf(item.glow, null) === true || (flagOf(item.glow, null) !== false && (Object.keys(item.enchants ?? {}).length > 0 || Object.keys(item['stored-enchants'] ?? {}).length > 0)),
    itemModel: item['item-model'] ?? known.itemModel ?? null,
    state: {
      customModelData: floats ? { floats } : undefined,
      damage: numberOf(item.damage, 0),
      maxDamage: known.maxDamage ?? 0,
      count: numberOf(item.amount, 1),
      dyedColor: color,
      potionColor: known.potionColor ?? null,
      fireworkColor: fireworkColor(item),
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
  render(item, known = {}, scale = SCALE, smooth = true) {
    const described = stateOf(item, known)
    const key = JSON.stringify([described, scale, smooth])
    if (!this.cache.has(key)) this.cache.set(key, this.draw(described, scale, smooth).catch(() => null))
    return this.cache.get(key)
  }

  /**
   * [smooth] draws four times as large and shrinks the result, so the edges are not stairs; the game itself draws without it, which is what
   * `smooth = false` gives.
   */
  async draw({ material, itemModel, state, glint, skin, banner, baseColor }, scale, smooth) {
    const oversample = smooth ? 4 : 1
    const entries = await this.entries(material, itemModel, state)
    const layers = []
    const needed = new Set()
    for (const entry of entries) {
      const made = await this.layer(entry, skin, banner, baseColor)
      for (const layer of [made].flat()) {
        if (!layer) continue
        layers.push(layer)
        for (const quad of layer.quads) if (quad.texture) needed.add(quad.texture)
      }
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
    const image = renderItem(layers, (key) => textures.get(key) ?? null, scale * oversample)
    if (glint) {
      const sheen = await this.assets.texture('minecraft', 'misc/enchanted_glint_item')
      if (sheen) addGlint(image, sheen)
    }
    return downsample(image, oversample)
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
  async layer(entry, skin = null, banner = [], baseColor = null) {
    const key = entry.model ?? entry.base
    if (!key) return null
    const model = await loadModel(this.assets, key)
    if (!model) return null
    if (entry.special && String(entry.special.type).replace(/^minecraft:/, '') === 'banner') {
      const built = bannerLayers(entry.special.color, banner)
      // The game lights the cloth of a banner at about three quarters, measured from icons it rendered; no light of the item matches it.
      return built.layers.map((l) => ({ quads: l.quads, display: model.display.gui, guiLight: model.guiLight ?? 'side', tints: l.tints, brightness: 0.766 }))
    }
    if (entry.special && String(entry.special.type).replace(/^minecraft:/, '') === 'shield') {
      const built = shieldLayers(baseColor, banner)
      // The game's icon of a shield, measured against one it rendered (the wiki's Invicon: 95 % of its pixels), sits where the base model's
      // translation of (2, 3, 0) would not put it: one pixel left of the middle and no higher. The shield model is not centred in the game.
      const display = { ...model.display.gui, translation: [-1, 0, 0] }
      return built.layers.map((l) => ({ quads: l.quads, display, guiLight: model.guiLight ?? 'side', tints: l.tints }))
    }
    if (entry.special) {
      const built = specialQuads(entry.special, skin ? `url:${skin}` : null)
      if (!built) return null // drawn by code in the game and not built here: better no picture than half of one
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
    if (entry.transformation) quads = transformQuads(quads, entry.transformation)
    return { quads, display: model.display.gui, guiLight: model.guiLight ?? 'side', tints: entry.tints, alpha: translucent(key) ? 'translucent' : undefined }
  }
}

/** Blocks the game draws in its translucent layer: glass, ice, slime and honey. Everything else with elements is solid or cutout. */
function translucent(modelKey) {
  const name = modelKey.replace(/^minecraft:/, '').replace(/^block\//, '')
  return /(^|_)glass(_pane)?($|_)|^ice$|^frosted_ice$|slime_block|honey_block|tinted_glass/.test(name) && !name.includes('bottle')
}

export { Assets, Model }
