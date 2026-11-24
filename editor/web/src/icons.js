// Item icon renderer for both inventory slots (unclipped, allowing models whose `display.gui` extends past the
// 16x16 slot to overflow seamlessly over adjacent hitbox slots) and sidebar/picker thumbnails (`contain: true`).

import { h } from './dom.js'
import { potionColor } from './potions.js'
import { settings as assetSettings } from './assets.js'

export const settings = {
  /** Fallback texture base URL when the 3D model renderer cannot draw an item. Dynamically tracks `assetSettings.version`. */
  get vanilla() {
    return `${assetSettings.base}/${assetSettings.version}/assets/minecraft/textures`
  },
  /** @type {import('./pack.js').Pack|null} */
  pack: null,
  /** @type {import('./itemrender.js').ItemRenderer|null} */
  renderer: null,
  potionColors: new Map(),
  resolved: new Map(),
}

function tile(label, seed) {
  let hash = 0
  for (const c of seed) hash = (hash * 31 + c.charCodeAt(0)) >>> 0
  return h('span', { class: 'tile', style: `background:hsl(${hash % 360} 35% 32%)` }, label)
}

export function parts(material) {
  const text = String(material ?? '').trim()
  const colon = text.indexOf(':')
  if (colon < 0) return { prefix: null, id: text.toLowerCase() }
  const prefix = text.slice(0, colon).toLowerCase()
  if (prefix === 'minecraft') return { prefix: null, id: text.slice(colon + 1).toLowerCase() }
  return { prefix, id: text.slice(colon + 1) }
}

export function potionSpec(item) {
  const type = item['potion-type'] ?? null
  const effects = (item.potion ?? []).map((e) => ({ type: e.type, amplifier: e.amplifier ?? 0 }))
  return type || effects.length ? { type, effects } : null
}

export const potionKey = (spec) => JSON.stringify(spec)

/**
 * Renders the icon of an item.
 * @param {object} item
 * @param {number} [scale]
 * @param {number} [ratio]
 * @param {{ contain?: boolean }} [options] Set `contain: true` for sidebar/picker thumbnails so oversized models fit inside the box.
 * @returns {HTMLElement}
 */
export function icon(item, scale = 3, ratio = 1, { contain = false } = {}) {
  const material = String(item.material ?? '')
  const { prefix, id } = parts(material)
  const label = (prefix ? prefix : id).replace(/_/g, ' ').split(' ').map((w) => w[0] ?? '').join('').slice(0, 3).toUpperCase()
  const slotPx = (16 * scale) / ratio
  const wrapper = h('span', { class: 'icon-wrap', style: `width:${slotPx}px;height:${slotPx}px` }, tile(label || '?', material || '?'))
  const spec = potionSpec(item)
  const color = spec ? (settings.potionColors.get(potionKey(spec)) ?? potionColor(spec.type, spec.effects)) : null
  const known = { ...(settings.resolved.get(material) ?? {}), ...(color !== null ? { potionColor: color } : {}) }
  if (!material.includes('%') && settings.renderer) {
    settings.renderer.render(item, known, scale).then((image) => {
      if (image) wrapper.replaceChildren(toCanvas(image, slotPx, ratio, contain))
      else fallback(wrapper, material, prefix, id, item, known, slotPx)
    })
  } else fallback(wrapper, material, prefix, id, item, known, slotPx)
  return wrapper
}

/**
 * Converts a rendered `{ width, height, offsetX, offsetY, data }` image into a `<canvas>`.
 * In inventory slots (`contain = false`), oversized models keep their exact Minecraft pixel scale and
 * offset (`left: offsetX/ratio`, `top: offsetY/ratio`) so multi-slot buttons & big items are NEVER clipped!
 * In thumbnails (`contain = true`), oversized models are fitted proportionally inside `slotPx × slotPx`.
 */
function toCanvas(image, slotPx, ratio, contain) {
  const ox = (image.offsetX ?? 0) / ratio
  const oy = (image.offsetY ?? 0) / ratio
  const cssW = image.width / ratio
  const cssH = image.height / ratio
  let style
  if (contain && (cssW > slotPx || cssH > slotPx || ox !== 0 || oy !== 0)) {
    const fit = Math.min(slotPx / cssW, slotPx / cssH)
    const w = cssW * fit
    const h2 = cssH * fit
    style = `left:${(slotPx - w) / 2}px;top:${(slotPx - h2) / 2}px;width:${w}px;height:${h2}px`
  } else {
    style = `left:${ox}px;top:${oy}px;width:${cssW}px;height:${cssH}px`
  }
  const canvas = h('canvas', { class: 'pixel', width: image.width, height: image.height, style })
  canvas.getContext('2d').putImageData(new ImageData(image.data, image.width, image.height), 0, 0)
  return canvas
}

function fallback(wrapper, material, prefix, id, item, known, slotPx) {
  const details = {
    material: known.material,
    itemModel: item['item-model'] ?? known.itemModel,
    customModelData: typeof item.model === 'number' ? [item.model] : known.customModelData,
  }
  const sources = []
  if (settings.pack) sources.push(...settings.pack.itemTextures(known.material ?? id, details))
  if (prefix === null && id && !id.includes('%')) {
    sources.push(`${settings.vanilla}/item/${id}.png`, `${settings.vanilla}/items/${id}.png`, `${settings.vanilla}/block/${id}.png`, `${settings.vanilla}/blocks/${id}.png`, `${settings.vanilla}/block/${id}_front.png`)
  } else if (known.material) {
    sources.push(`${settings.vanilla}/item/${known.material}.png`, `${settings.vanilla}/items/${known.material}.png`)
  }
  if (sources.length) load(wrapper, sources, slotPx)
}

async function load(wrapper, urls, slotPx) {
  const next = await urls.shift()
  if (!next) return urls.length ? load(wrapper, urls, slotPx) : undefined
  const image = new Image()
  image.className = 'pixel'
  image.style.cssText = `left:0;top:0;width:${slotPx}px;height:${slotPx}px`
  image.onload = () => wrapper.append(image)
  image.onerror = () => load(wrapper, urls, slotPx)
  image.src = next
}
