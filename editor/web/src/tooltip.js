// The tooltip of an item, drawn as the game draws it: the name in the color of the rarity, the enchantments in gray, the lore in dark purple, in
// a box made from the game's own sprites. What the editor knows of the item is only what the menu file says.

import { nineSlice } from './ninepatch.js'

const RARITY = { COMMON: '#ffffff', UNCOMMON: '#ffff55', RARE: '#55ffff', EPIC: '#ff55ff' }
const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X']
/** Enchantments that have one level, which the game shows without a number. */
const SINGLE = new Set(['mending', 'silk_touch', 'infinity', 'flame', 'channeling', 'aqua_affinity', 'multishot', 'binding_curse', 'vanishing_curse'])
const CURSES = new Set(['binding_curse', 'vanishing_curse'])

/** The level as the game writes it: roman numerals up to 10, a number beyond. */
export function level(value) {
  return ROMAN[value] ?? String(value)
}

/**
 * The lines of the tooltip of [item], first the name. Each is `{text, color}`: the text in the codes of a menu, the color for what it does
 * not color itself.
 * @param {object} item the item as the menu writes it
 * @param {Record<string, string>} lang the game's names
 * @param {{itemName?: string}} [known]
 */
export function tooltipLines(item, lang, known = {}) {
  const material = String(item.material ?? '').replace(/^minecraft:/, '')
  const base = material.includes(':') ? null : material
  const fallback = lang[`item.minecraft.${base}`] ?? lang[`block.minecraft.${base}`] ?? known.itemName ?? material
  const lines = [{ text: item.name ?? item['item-name'] ?? fallback, color: RARITY[item.rarity] ?? '#ffffff' }]
  const hidden = new Set((item.hide ?? []).map((h) => h.replace(/^minecraft:/, '')))
  if (!hidden.has('enchantments') && !item['hide-tooltip']) {
    for (const [key, value] of Object.entries({ ...item.enchants, ...item['stored-enchants'] })) {
      const id = key.replace(/^minecraft:/, '')
      const name = lang[`enchantment.minecraft.${id}`] ?? id
      lines.push({ text: SINGLE.has(id) && value === 1 ? name : `${name} ${level(value)}`, color: CURSES.has(id) ? '#ff5555' : '#aaaaaa' })
    }
  }
  for (const text of item.lore ?? []) lines.push({ text, color: '#aa00aa' })
  if (item.unbreakable && !hidden.has('unbreakable')) lines.push({ text: 'Unbreakable', color: '#5555ff' })
  return lines
}

/**
 * The tooltip as a canvas: the box and the text.
 * @param {{text: string, color: string}[]} lines
 * @param {import('./text.js').Text} text
 * @param {import('./assets.js').Assets} assets
 */
export async function drawTooltip(lines, text, assets, SCALE = 3) {
  const widths = await Promise.all(lines.map((l) => text.measure(l.text)))
  const textWidth = Math.max(...widths)
  // Ten pixels a line, and two more after the name when there is more than the name.
  const textHeight = lines.length * 10 - (lines.length === 1 ? 2 : 0) + (lines.length > 1 ? 2 : 0)
  const pad = 3 + 9
  const canvas = document.createElement('canvas')
  canvas.width = (textWidth + 2 * pad) * SCALE
  canvas.height = (textHeight + 2 * pad) * SCALE
  const ctx = canvas.getContext('2d')
  const [background, frame] = await Promise.all([
    assets.texture('minecraft', 'gui/sprites/tooltip/background'),
    assets.texture('minecraft', 'gui/sprites/tooltip/frame'),
  ])
  if (background) {
    nineSlice(ctx, background, 0, 0, canvas.width, canvas.height, 9, SCALE)
    nineSlice(ctx, frame, 0, 0, canvas.width, canvas.height, 10, SCALE)
  } else {
    ctx.fillStyle = 'rgba(16, 0, 16, 0.94)'
    ctx.fillRect(3 * SCALE, 3 * SCALE, canvas.width - 6 * SCALE, canvas.height - 6 * SCALE)
  }
  let y = pad
  for (const [i, line] of lines.entries()) {
    await text.draw(ctx, line.text, pad * SCALE, y * SCALE, { scale: SCALE, color: line.color, shadow: true })
    y += i === 0 && lines.length > 1 ? 12 : 10
  }
  return canvas
}
