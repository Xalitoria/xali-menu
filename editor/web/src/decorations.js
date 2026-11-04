// What the game draws over an item in a slot: the count in the bottom right corner, and the bar of an item that is worn.

import { shadowOf } from './text.js'

const SCALE = 3

/** The color of the bar of a worn item: from green to red as the item wears out. */
export function barColor(fraction) {
  const hue = Math.max(0, Math.min(1, fraction)) / 3
  const [r, g, b] = hsv(hue, 1, 1)
  return `rgb(${r}, ${g}, ${b})`
}

function hsv(h, s, v) {
  const i = Math.floor(h * 6)
  const f = h * 6 - i
  const p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s)
  const [r, g, b] = [[v, t, p], [q, v, p], [p, v, t], [p, q, v], [t, p, v], [v, p, q]][i % 6]
  return [Math.round(r * 255), Math.round(g * 255), Math.round(b * 255)]
}

/** The width of the bar of an item with [damage] of [maxDamage] used, in pixels of the game (13 at most). */
export function barWidth(damage, maxDamage) {
  return Math.max(0, Math.min(13, Math.round(13 - (damage * 13) / maxDamage)))
}

/**
 * A canvas the size of a slot with what goes over the item: the count when it is more than one, and the durability bar when the item
 * has a known [maxDamage] and is worn.
 * @param {{amount?: number, damage?: number}} item
 * @param {import('./text.js').Text} text
 * @param {number} [maxDamage]
 */
export function decorations(item, text, maxDamage = 0) {
  const canvas = document.createElement('canvas')
  canvas.className = 'decorations'
  canvas.width = 18 * SCALE
  canvas.height = 18 * SCALE
  const ctx = canvas.getContext('2d')
  const origin = 1 * SCALE // the item is drawn one pixel in from the corner of its slot
  const count = item.amount ?? 1
  if (maxDamage > 0 && (item.damage ?? 0) > 0) {
    const width = barWidth(item.damage, maxDamage)
    ctx.fillStyle = '#000000'
    ctx.fillRect(origin + 2 * SCALE, origin + 13 * SCALE, 13 * SCALE, 2 * SCALE)
    ctx.fillStyle = barColor((maxDamage - item.damage) / maxDamage)
    ctx.fillRect(origin + 2 * SCALE, origin + 13 * SCALE, width * SCALE, 1 * SCALE)
  }
  if (count !== 1) {
    const label = String(count)
    text.measure(label).then((width) => text.draw(ctx, label, origin + (19 - 2 - width) * SCALE, origin + (6 + 3) * SCALE, { scale: SCALE, color: count < 1 ? '#ff5555' : '#ffffff', shadow: true })).catch(() => {})
  }
  return canvas
}

export { shadowOf }
