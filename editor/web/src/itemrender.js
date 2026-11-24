// Version-aware software item renderer shared by XaliMenu and XaliCrates web editors.
// Renders modern 1.21.4+ (`pack_format >= 46`) `items/*.json` definitions AND legacy 1.8 – 1.21.3
// `models/item/*.json` + `overrides` predicates, without clipping oversized `display.gui` models!

import { numberOf, flagOf } from './dynamic.js'
import { Assets, split } from './assets.js'
import { Model, loadModel, bakeElements, bakeLayers, transformQuads } from './bake.js'
import { chooseModels, matchLegacyOverrides } from './itemdef.js'
import { renderItem, addGlint, downsample } from './raster.js'
import { specialQuads, bannerLayers, shieldLayers } from './special.js'

export const SCALE = 3

const PLAYER_NAME = /^[A-Za-z0-9_]{1,16}$/
const TEXTURE_URL = /^https?:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]{16,128}$/i

function decodeTextureBase64(b64) {
  try {
    const json = JSON.parse(atob(String(b64 ?? '').trim()))
    return json?.textures?.SKIN?.url?.replace(/^http:/, 'https:') ?? null
  } catch {
    return null
  }
}

export function skinUrl(material) {
  const text = String(material ?? '')
  if (!text.startsWith('texture:')) return null
  return decodeTextureBase64(text.slice(8))
}

export function skinSpec(item) {
  const fromValue = (raw) => {
    const text = String(raw ?? '').trim()
    if (!text || /[%{]/.test(text)) return null
    if (TEXTURE_URL.test(text)) return `url:${text.replace(/^http:/, 'https:')}`
    if (text.startsWith('texture:')) {
      const url = decodeTextureBase64(text.slice(8))
      return url ? `url:${url}` : null
    }
    if (text.startsWith('head:')) {
      const name = text.slice(5).trim()
      return PLAYER_NAME.test(name) ? `player:${name}` : null
    }
    if (text.length >= 40) {
      const url = decodeTextureBase64(text)
      if (url) return `url:${url}`
    }
    return PLAYER_NAME.test(text) ? `player:${text}` : null
  }

  if (typeof item?.skull === 'string' && item.skull.trim()) {
    const found = fromValue(item.skull)
    if (found) return found
  }
  const mat = String(item?.material ?? '').trim()
  if (mat.startsWith('texture:') || mat.startsWith('head:')) {
    const found = fromValue(mat)
    if (found) return found
  }
  const cleanMat = mat.replace(/^minecraft:/, '').toLowerCase()
  if ((cleanMat === 'player_head' || mat.startsWith('head:') || mat.startsWith('texture:')) && typeof item?.nbt === 'string' && item.nbt.trim()) {
    const nbt = item.nbt
    const urlMatch = nbt.match(/https?:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]{16,128}/i)
    if (urlMatch) return `url:${urlMatch[0].replace(/^http:/, 'https:')}`
    const valueMatch = nbt.match(/\bvalue\s*[:=]\s*["']([A-Za-z0-9+/=]{40,})["']/)
    if (valueMatch) {
      const url = decodeTextureBase64(valueMatch[1])
      if (url) return `url:${url}`
    }
    const nameMatch = nbt.match(/profile\s*[:=]\s*\{[^}]*\bname\s*[:=]\s*["']?([A-Za-z0-9_]{1,16})["']?/i)
    if (nameMatch) return `player:${nameMatch[1]}`
  }
  return null
}

export function fireworkColor(item) {
  const colors = (item.firework?.effects?.[0]?.colors ?? []).filter((c) => /^#[0-9a-f]{6}$/i.test(String(c))).map((c) => parseInt(c.slice(1), 16))
  if (colors.length === 0) return null
  const mean = (shift) => Math.trunc(colors.reduce((sum, c) => sum + ((c >> shift) & 255), 0) / colors.length)
  return (mean(16) << 16) | (mean(8) << 8) | mean(0)
}

export function stateOf(item, known = {}) {
  const color = typeof item.color === 'string' && /^#[0-9a-f]{6}$/i.test(item.color) ? parseInt(item.color.slice(1), 16) : null
  const model = numberOf(item.model ?? item['custom-model-data'], null)
  const floats = model !== null && model !== 0 ? [model] : known.customModelData
  const text = String(item.material ?? '')
  const head = text.startsWith('head:') || text.startsWith('texture:')
  return {
    material: head ? 'player_head' : (known.material ?? text.replace(/^minecraft:/, '')),
    skin: skinSpec(item),
    banner: Array.isArray(item.banner) ? item.banner : (Array.isArray(item.patterns) ? item.patterns : []),
    baseColor: typeof item['base-color'] === 'string' ? item['base-color'] : null,
    glint: flagOf(item.glow, null) === true || (flagOf(item.glow, null) !== false && (Object.keys(item.enchants ?? item.enchantments ?? {}).length > 0 || Object.keys(item['stored-enchants'] ?? {}).length > 0)),
    itemModel: item['item-model'] ?? known.itemModel ?? null,
    state: {
      customModelData: floats ? { floats } : undefined,
      damage: numberOf(item.damage, 0),
      maxDamage: known.maxDamage ?? 0,
      count: numberOf(item.amount, 1),
      dyedColor: color,
      potionColor: known.potionColor ?? null,
      fireworkColor: fireworkColor(item),
      trimMaterial: typeof item.trim?.material === 'string' && item.trim.material ? item.trim.material.replace(/^minecraft:/, '').toLowerCase() : null,
      components: item.trim ? ['trim'] : [],
    },
  }
}

export class ItemRenderer {
  /** @param {Assets} assets */
  constructor(assets) {
    this.assets = assets
    this.cache = new Map()
  }

  render(item, known = {}, scale = SCALE, smooth = true) {
    const described = stateOf(item, known)
    const key = JSON.stringify([described, scale, smooth])
    if (!this.cache.has(key)) this.cache.set(key, this.draw(described, scale, smooth).catch(() => null))
    return this.cache.get(key)
  }

  async draw({ material, itemModel, state, glint, skin, banner, baseColor }, scale, smooth) {
    const oversample = smooth ? 4 : 1
    const { entries, hasDefinition, oversizedInGui } = await this.resolveEntries(material, itemModel, state)
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
      if (key.startsWith('player:')) { textures.set(key, await this.assets.fromPlayer(key.slice(7))); return }
      const { ns, path } = split(key)
      textures.set(key, await this.assets.texture(ns, path))
    }))
    for (const layer of layers) for (const quad of layer.quads) if ((quad.texture?.startsWith('url:') || quad.texture?.startsWith('player:')) && !textures.get(quad.texture)) quad.texture = 'minecraft:entity/player/wide/steve'
    if (layers.some((l) => l.quads.some((q) => q.texture === 'minecraft:entity/player/wide/steve')) && !textures.has('minecraft:entity/player/wide/steve')) {
      textures.set('minecraft:entity/player/wide/steve', await this.assets.texture('minecraft', 'entity/player/wide/steve'))
    }
    // Minecraft 1.21.6+ (pack_format >= 63) clips items in GUIs unless the item definition sets `oversized_in_gui: true`.
    // Pass `oversample` as alignment so unclipped bounds divide cleanly in `downsample`.
    const clipToSlot = Boolean(this.assets.versionInfo?.clipsNonOversizedInGui && hasDefinition && !oversizedInGui)
    const image = renderItem(layers, (key) => textures.get(key) ?? null, scale * oversample, oversample, clipToSlot)
    if (glint) {
      const sheen = await this.assets.texture('minecraft', 'misc/enchanted_glint_item')
      if (sheen) addGlint(image, sheen)
    }
    return downsample(image, oversample)
  }

  /**
   * Resolves item model entries according to the active Minecraft version & pack format:
   *   - Modern (`packFormat >= 46` / MC >= 1.21.4): checks `assets/<ns>/items/<path>.json` first, then falls back to `models/item/<path>.json` + legacy overrides.
   *   - Legacy (`packFormat < 46` / MC 1.8 – 1.21.3): checks `assets/<ns>/models/item/<path>.json` + legacy `overrides` first, with fallback to `items/<path>.json`.
   */
  async resolveEntries(material, itemModel, state) {
    const preferModern = this.assets.versionInfo ? this.assets.versionInfo.hasItemDefinitions : true
    const { ns, path } = split(itemModel ?? material)

    const tryModern = async () => {
      const definition = await this.assets.json(ns, 'items', path)
      if (!definition) return null
      const text = JSON.stringify(definition)
      const colormaps = {}
      if (text.includes('minecraft:grass')) colormaps.grass = await this.assets.texture('minecraft', 'colormap/grass')
      if (text.includes('minecraft:foliage')) colormaps.foliage = await this.assets.texture('minecraft', 'colormap/foliage')
      const entries = chooseModels(definition, { ...state, colormaps })
      return { entries, hasDefinition: true, oversizedInGui: definition.oversized_in_gui === true }
    }

    const tryLegacy = async () => {
      const baseKey = itemModel && !this.assets.versionInfo?.hasItemModelComponent ? material : (itemModel ?? material)
      const target = split(baseKey)
      const asItem = await this.assets.json(target.ns, 'models', `item/${target.path}`)
      if (asItem) {
        const overridden = matchLegacyOverrides(asItem, state)
        const chosen = overridden ?? `${target.ns}:item/${target.path}`
        return { entries: [{ model: chosen, tints: legacyTints(target.path, state) }], hasDefinition: false, oversizedInGui: false }
      }
      // Special built-in entities on legacy versions (player_head, chest, shulker_box, shield, banner)
      const special = legacySpecial(target.path)
      if (special) return { entries: [special], hasDefinition: false, oversizedInGui: false }
      return { entries: [{ model: `${target.ns}:block/${target.path}`, tints: legacyTints(target.path, state) }], hasDefinition: false, oversizedInGui: false }
    }

    if (preferModern) {
      const modern = await tryModern()
      if (modern && modern.entries.length > 0) return modern
      return tryLegacy()
    }
    const legacy = await tryLegacy()
    if (legacy && legacy.entries.length > 0) return legacy
    return (await tryModern()) ?? { entries: [], hasDefinition: false, oversizedInGui: false }
  }

  async entries(material, itemModel, state) {
    return (await this.resolveEntries(material, itemModel, state)).entries
  }

  async layer(entry, skin = null, banner = [], baseColor = null) {
    const key = entry.model ?? entry.base
    if (!key) return null
    const model = await loadModel(this.assets, key)
    if (!model) return null
    if (entry.special && String(entry.special.type).replace(/^minecraft:/, '') === 'banner') {
      const built = bannerLayers(entry.special.color, banner)
      return built.layers.map((l) => ({ quads: l.quads, display: model.display.gui, guiLight: model.guiLight ?? 'side', tints: l.tints, brightness: 0.766 }))
    }
    if (entry.special && String(entry.special.type).replace(/^minecraft:/, '') === 'shield') {
      const built = shieldLayers(baseColor, banner)
      const display = { ...model.display.gui, translation: [-1, 0, 0] }
      return built.layers.map((l) => ({ quads: l.quads, display, guiLight: model.guiLight ?? 'side', tints: l.tints }))
    }
    if (entry.special) {
      const built = specialQuads(entry.special, skin || null)
      if (!built) return null
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

function legacyTints(itemName, state) {
  const n = itemName.toLowerCase()
  if (n.startsWith('leather_') && state.dyedColor != null) return [state.dyedColor]
  if (n.includes('potion') && state.potionColor != null) return [state.potionColor]
  if (n === 'firework_star' && state.fireworkColor != null) return [null, state.fireworkColor]
  return []
}

function legacySpecial(itemName) {
  const n = itemName.toLowerCase()
  if (n === 'player_head' || n === 'skull') return { special: { type: 'minecraft:head', kind: 'player' }, base: 'minecraft:item/template_skull', tints: [] }
  if (n === 'chest') return { special: { type: 'minecraft:chest', texture: 'minecraft:normal' }, base: 'minecraft:item/chest', tints: [] }
  if (n === 'ender_chest') return { special: { type: 'minecraft:chest', texture: 'minecraft:ender' }, base: 'minecraft:item/chest', tints: [] }
  if (n === 'shield') return { special: { type: 'minecraft:shield' }, base: 'minecraft:item/shield', tints: [] }
  return null
}

function translucent(modelKey) {
  const name = modelKey.replace(/^minecraft:/, '').replace(/^block\//, '')
  return /(^|_)glass(_pane)?($|_)|^ice$|^frosted_ice$|slime_block|honey_block|tinted_glass/.test(name) && !name.includes('bottle')
}

export { Assets, Model }
