// The window of a menu, drawn as the game draws it: the background texture of the inventory type, the title in the font of the game
// (with the glyphs of the resource pack), and the label of the player's inventory. The slots of the menu lie over it at the places the
// texture has them.

import { blit } from './ninepatch.js'
import { slotCount } from './check.js'

/** Backgrounds by inventory type: the texture, its size on screen, the slot columns and the place of the first slot. */
const KINDS = {
  hopper: { texture: 'gui/container/hopper', width: 176, height: 133, columns: 5, x: 44, y: 20, inventoryLabel: 39, player: { main: 51, hotbar: 109 } },
  dispenser: { texture: 'gui/container/dispenser', width: 176, height: 166, columns: 3, x: 62, y: 17, inventoryLabel: 72, player: { main: 84, hotbar: 142 } },
  dropper: { texture: 'gui/container/dispenser', width: 176, height: 166, columns: 3, x: 62, y: 17, inventoryLabel: 72, player: { main: 84, hotbar: 142 } },
}

/**
 * Where the slots of [menu] are and how big the window is, in pixels of the game.
 * @returns {{width: number, height: number, columns: number, x: number, y: number, rows: number, texture: string, chest: boolean, inventoryLabel: number, player: {main: number, hotbar: number}}}
 *   [player] is where the player's inventory is: the y of its first row and of its hotbar; the x of the first column is 8
 */
export function geometry(menu) {
  const type = String(menu.type ?? 'chest').toLowerCase()
  if (KINDS[type]) {
    const kind = KINDS[type]
    return { ...kind, rows: Math.ceil(slotCount(menu) / kind.columns), chest: false }
  }
  const rows = Math.max(1, Math.ceil(slotCount(menu) / 9))
  return { texture: 'gui/container/generic_54', width: 176, height: 114 + rows * 18, columns: 9, x: 8, y: 18, rows, chest: true, inventoryLabel: 20 + rows * 18, player: { main: 103 + (rows - 4) * 18, hotbar: 161 + (rows - 4) * 18 } }
}

export class WindowView {
  /** @param {import('./text.js').Text} text @param {import('./assets.js').Assets} assets */
  constructor(text, assets) {
    this.text = text
    this.assets = assets
  }

  /**
   * The window as a canvas, filled in as the textures and fonts arrive. [scale] screen pixels make a pixel of the game, and [ratio] is the pixel ratio of the page. [margin] pixels of the
   * game are left around it (a number, or `{x, y}` for different widths), for a glyph that is bigger than the window; [slots] draws the outline of each slot.
   */
  draw(menu, { scale = 3, ratio = 1, margin = 0, slots = false } = {}) {
    const g = geometry(menu)
    const SCALE = scale
    const m = { x: (typeof margin === 'number' ? margin : margin.x) * SCALE, y: (typeof margin === 'number' ? margin : margin.y) * SCALE }
    const canvas = document.createElement('canvas')
    canvas.className = 'window-canvas'
    canvas.width = g.width * SCALE + 2 * m.x
    canvas.height = g.height * SCALE + 2 * m.y
    // [scale] is in screen pixels; on the page the canvas is that many screen pixels, whatever the zoom.
    canvas.style.width = `${canvas.width / ratio}px`
    canvas.style.height = `${canvas.height / ratio}px`
    this.fill(canvas, menu, g, m, slots, SCALE).catch(() => {})
    return canvas
  }

  async fill(canvas, menu, g, m, slots, SCALE) {
    const ctx = canvas.getContext('2d')
    if (m.x || m.y) { ctx.fillStyle = '#26292f'; ctx.fillRect(0, 0, canvas.width, canvas.height) }
    const texture = await this.assets.texture('minecraft', g.texture)
    if (texture && g.chest) {
      // The top of the texture has the rows, the bottom of it the player's inventory.
      const top = g.rows * 18 + 17
      blit(ctx, texture, m.x, m.y, 0, 0, g.width, top, SCALE)
      blit(ctx, texture, m.x, m.y + top * SCALE, 0, 126, g.width, 96, SCALE)
    } else if (texture) {
      blit(ctx, texture, m.x, m.y, 0, 0, g.width, g.height, SCALE)
    } else {
      ctx.fillStyle = '#c6c6c6'
      ctx.fillRect(m.x, m.y, g.width * SCALE, g.height * SCALE)
    }
    await this.text.draw(ctx, menu.title ?? '', m.x + 8 * SCALE, m.y + 6 * SCALE, { scale: SCALE, color: '#404040', shadow: false })
    await this.text.draw(ctx, 'Inventory', m.x + 8 * SCALE, m.y + g.inventoryLabel * SCALE, { scale: SCALE, color: '#404040', shadow: false })
    if (slots) {
      ctx.strokeStyle = 'rgba(0,0,0,0.35)'
      ctx.lineWidth = 1
      for (let slot = 0; slot < g.rows * g.columns; slot++) {
        ctx.strokeRect(m.x + (g.x - 1 + (slot % g.columns) * 18) * SCALE + 0.5, m.y + (g.y - 1 + Math.floor(slot / g.columns) * 18) * SCALE + 0.5, 18 * SCALE - 1, 18 * SCALE - 1)
      }
    }
  }
}

/**
 * The place of the slot [index] of the player's inventory, 0 to 26 for the three rows and 27 to 35 for the hotbar, in pixels of the game from
 * the corner of the window; this is where the item of the slot is drawn.
 */
export function playerSlot(g, index) {
  if (index >= 27) return { x: 8 + (index - 27) * 18, y: g.player.hotbar }
  return { x: 8 + (index % 9) * 18, y: g.player.main + Math.floor(index / 9) * 18 }
}

/** The slots of the player's inventory that a menu can draw in: 0 to 8 the hotbar, 9 to 35 the rows above it. */
export const INVENTORY_SLOTS = 36

/** The index [playerSlot] takes for [slot] of the player's inventory as the game numbers it: the rows come first on the screen, the hotbar last. */
export function inventoryIndex(slot) {
  return slot >= 9 ? slot - 9 : 27 + slot
}
