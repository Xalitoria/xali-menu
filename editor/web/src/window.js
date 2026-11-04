// The window of a menu, drawn as the game draws it: the background texture of the inventory type, the title in the font of the game
// (with the glyphs of the resource pack), and the label of the player's inventory. The slots of the menu lie over it at the places the
// texture has them.

import { blit } from './ninepatch.js'
import { slotCount } from './check.js'

/** Backgrounds by inventory type: the texture, its size on screen, the slot columns and the place of the first slot. */
const KINDS = {
  hopper: { texture: 'gui/container/hopper', width: 176, height: 133, columns: 5, x: 44, y: 20, inventoryLabel: 39 },
  dispenser: { texture: 'gui/container/dispenser', width: 176, height: 166, columns: 3, x: 62, y: 17, inventoryLabel: 72 },
  dropper: { texture: 'gui/container/dispenser', width: 176, height: 166, columns: 3, x: 62, y: 17, inventoryLabel: 72 },
}

/**
 * Where the slots of [menu] are and how big the window is, in pixels of the game.
 * @returns {{width: number, height: number, columns: number, x: number, y: number, rows: number, texture: string, chest: boolean, inventoryLabel: number}}
 */
export function geometry(menu) {
  const type = String(menu.type ?? 'chest').toLowerCase()
  if (KINDS[type]) {
    const kind = KINDS[type]
    return { ...kind, rows: Math.ceil(slotCount(menu) / kind.columns), chest: false }
  }
  const rows = Math.max(1, Math.ceil(slotCount(menu) / 9))
  return { texture: 'gui/container/generic_54', width: 176, height: 114 + rows * 18, columns: 9, x: 8, y: 18, rows, chest: true, inventoryLabel: 20 + rows * 18 }
}

export class WindowView {
  /** @param {import('./text.js').Text} text @param {import('./assets.js').Assets} assets */
  constructor(text, assets) {
    this.text = text
    this.assets = assets
  }

  /**
   * The window as a canvas, filled in as the textures and fonts arrive. [scale] screen pixels make a pixel of the game. [margin] pixels of the
   * game are left around it, for a glyph that is bigger than the window; [slots] draws the outline of each slot.
   */
  draw(menu, { scale = 3, margin = 0, slots = false } = {}) {
    const g = geometry(menu)
    const SCALE = scale
    const m = margin * SCALE
    const canvas = document.createElement('canvas')
    canvas.className = 'window-canvas'
    canvas.width = g.width * SCALE + 2 * m
    canvas.height = g.height * SCALE + 2 * m
    this.fill(canvas, menu, g, m, slots, SCALE).catch(() => {})
    return canvas
  }

  async fill(canvas, menu, g, m, slots, SCALE) {
    const ctx = canvas.getContext('2d')
    if (m) { ctx.fillStyle = '#26292f'; ctx.fillRect(0, 0, canvas.width, canvas.height) }
    const texture = await this.assets.texture('minecraft', g.texture)
    if (texture && g.chest) {
      // The top of the texture has the rows, the bottom of it the player's inventory.
      const top = g.rows * 18 + 17
      blit(ctx, texture, m, m, 0, 0, g.width, top, SCALE)
      blit(ctx, texture, m, m + top * SCALE, 0, 126, g.width, 96, SCALE)
    } else if (texture) {
      blit(ctx, texture, m, m, 0, 0, g.width, g.height, SCALE)
    } else {
      ctx.fillStyle = '#c6c6c6'
      ctx.fillRect(m, m, g.width * SCALE, g.height * SCALE)
    }
    await this.text.draw(ctx, menu.title ?? '', m + 8 * SCALE, m + 6 * SCALE, { scale: SCALE, color: '#404040', shadow: false })
    await this.text.draw(ctx, 'Inventory', m + 8 * SCALE, m + g.inventoryLabel * SCALE, { scale: SCALE, color: '#404040', shadow: false })
    if (slots) {
      ctx.strokeStyle = 'rgba(0,0,0,0.35)'
      ctx.lineWidth = 1
      for (let slot = 0; slot < g.rows * g.columns; slot++) {
        ctx.strokeRect(m + (g.x + (slot % g.columns) * 18) * SCALE + 0.5, m + (g.y + Math.floor(slot / g.columns) * 18) * SCALE + 0.5, 18 * SCALE - 1, 18 * SCALE - 1)
      }
    }
  }
}
