// The picture of an item in a slot: from the resource pack when one is loaded, otherwise from a public copy of the game's
// textures, otherwise a colored tile with the first letters of its name. The tile is always there first, so the grid never waits.

import { h } from './dom.js'

/** Where the textures of the game are read from when no pack has them. The page can change it. */
export const settings = {
  vanilla: 'https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.4/assets/minecraft/textures',
  /** @type {import('./pack.js').Pack|null} */
  pack: null,
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

/**
 * @param {string} material
 * @param {{model?: string, itemModel?: string}} [extra] the item model key and custom model data of the item
 * @returns {HTMLElement}
 */
export function icon(material, extra = {}) {
  const { prefix, id } = parts(material)
  const label = (prefix ? prefix : id).replace(/_/g, ' ').split(' ').map((w) => w[0] ?? '').join('').slice(0, 3).toUpperCase()
  const wrapper = h('span', { class: 'icon-wrap' }, tile(label || '?', material || '?'))
  const known = settings.resolved.get(material) ?? {}
  const details = {
    material: known.material,
    itemModel: extra.itemModel ?? known.itemModel,
    customModelData: extra.model !== undefined ? [extra.model] : known.customModelData,
  }
  const sources = []
  if (settings.pack) sources.push(...settings.pack.itemTextures(known.material ?? id, details))
  if (prefix === null && id && !id.includes('%')) {
    sources.push(`${settings.vanilla}/item/${id}.png`, `${settings.vanilla}/block/${id}.png`, `${settings.vanilla}/block/${id}_front.png`)
  } else if (known.material) {
    sources.push(`${settings.vanilla}/item/${known.material}.png`)
  }
  if (sources.length) load(wrapper, sources)
  return wrapper
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
