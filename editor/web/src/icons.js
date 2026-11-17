// The picture of an item in a slot: from the resource pack when one is loaded, otherwise from a public copy of the game's
// textures, otherwise a colored tile with the first letters of its name. The tile is always there first, so the grid never waits.

import { h } from './dom.js'
import { potionColor } from './potions.js'

/** Where the textures of the game are read from when no pack has them. The page can change it. */
export const settings = {
  vanilla: 'https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.4/assets/minecraft/textures',
  /** @type {import('./pack.js').Pack|null} */
  pack: null,
  /** @type {import('./itemrender.js').ItemRenderer|null} draws items from their models; made again when the pack changes */
  renderer: null,
  /** The color of each potion the server worked out, by `potionKey`. */
  potionColors: new Map(),
  /** What the server's plugins say custom items are: material (key) to `{material, itemModel, customModelData}`. */
  resolved: new Map(),
}

function tile(label, seed) {
  let hash = 0
  for (const c of seed) hash = (hash * 31 + c.charCodeAt(0)) >>> 0
  return h('span', { class: 'tile', style: `background:hsl(${hash % 360} 35% 32%)` }, label)
}

/** The name of the item without `minecraft:`, and the source prefix if it has one (`head:Steve` → `head`). */
export function parts(material) {
  const text = String(material ?? '').trim()
  const colon = text.indexOf(':')
  if (colon < 0) return { prefix: null, id: text.toLowerCase() }
  const prefix = text.slice(0, colon).toLowerCase()
  if (prefix === 'minecraft') return { prefix: null, id: text.slice(colon + 1).toLowerCase() }
  return { prefix, id: text.slice(colon + 1) }
}

/** What identifies the potion of an item: its base type and its custom effects, or null if it has neither. */
export function potionSpec(item) {
  const type = item['potion-type'] ?? null
  const effects = (item.potion ?? []).map((e) => ({ type: e.type, amplifier: e.amplifier ?? 0 }))
  return type || effects.length ? { type, effects } : null
}

export const potionKey = (spec) => JSON.stringify(spec)

/**
 * The icon of a menu item: a colored tile at once, then the item as the game draws it when the model and its textures are found,
 * otherwise a picture from the pack or from a public copy of the textures.
 * @param {object} item the item as the menu writes it
 * @returns {HTMLElement}
 */
export function icon(item, scale = 3, ratio = 1) {
  const material = String(item.material ?? '')
  const { prefix, id } = parts(material)
  const label = (prefix ? prefix : id).replace(/_/g, ' ').split(' ').map((w) => w[0] ?? '').join('').slice(0, 3).toUpperCase()
  const wrapper = h('span', { class: 'icon-wrap', style: `width:${(16 * scale) / ratio}px;height:${(16 * scale) / ratio}px` }, tile(label || '?', material || '?'))
  const spec = potionSpec(item)
  // What the server said the potion's color is, else what the colors of the game's effects make of it.
  const color = spec ? (settings.potionColors.get(potionKey(spec)) ?? potionColor(spec.type, spec.effects)) : null
  const known = { ...(settings.resolved.get(material) ?? {}), ...(color !== null ? { potionColor: color } : {}) }
  if (!material.includes('%') && settings.renderer) {
    // [scale] is in screen pixels per pixel of the game, so the picture has a pixel for each pixel of the screen.
    settings.renderer.render(item, known, scale).then((image) => {
      if (image) wrapper.replaceChildren(toCanvas(image, (16 * scale) / ratio))
      else fallback(wrapper, material, prefix, id, item, known)
    })
  } else fallback(wrapper, material, prefix, id, item, known)
  return wrapper
}

/** The pixels of a render as a canvas. */
function toCanvas(image, size) {
  const canvas = h('canvas', { class: 'pixel', width: image.width, height: image.height, style: `width:${size}px;height:${size}px` })
  canvas.getContext('2d').putImageData(new ImageData(image.data, image.width, image.height), 0, 0)
  return canvas
}

/** What was used before items were drawn from their models: the texture with the name of the item. */
function fallback(wrapper, material, prefix, id, item, known) {
  const details = {
    material: known.material,
    itemModel: item['item-model'] ?? known.itemModel,
    customModelData: typeof item.model === 'number' ? [item.model] : known.customModelData,
  }
  const sources = []
  if (settings.pack) sources.push(...settings.pack.itemTextures(known.material ?? id, details))
  if (prefix === null && id && !id.includes('%')) {
    sources.push(`${settings.vanilla}/item/${id}.png`, `${settings.vanilla}/block/${id}.png`, `${settings.vanilla}/block/${id}_front.png`)
  } else if (known.material) {
    sources.push(`${settings.vanilla}/item/${known.material}.png`)
  }
  if (sources.length) load(wrapper, sources)
}

/** Tries each URL in turn and puts the first picture that loads over the tile. */
async function load(wrapper, urls) {
  const next = await urls.shift()
  if (!next) return urls.length ? load(wrapper, urls) : undefined
  const image = new Image()
  image.className = 'pixel'
  image.onload = () => wrapper.append(image)
  image.onerror = () => load(wrapper, urls)
  image.src = next
}
