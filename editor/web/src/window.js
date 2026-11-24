// The window of a menu or crate animation, drawn as the game draws it: the background texture of the inventory type,
// the title in the font of the game (with pack glyphs & Nexo shifts), and the player's inventory label.

import { blit } from './ninepatch.js'

const CONTAINER_SIZES = { hopper: 5, dispenser: 9, dropper: 9, crafter: 9, shulker_box: 27 }

function containerSlotCount(menu) {
  const type = String(menu.type ?? 'chest').toLowerCase()
  return type === 'chest' ? (menu.size ?? 54) : (CONTAINER_SIZES[type] ?? menu.size ?? 54)
}

const KINDS = {
  hopper: { texture: 'gui/container/hopper', width: 176, height: 133, columns: 5, x: 44, y: 20, inventoryLabel: 39, player: { main: 51, hotbar: 109 } },
  dispenser: { texture: 'gui/container/dispenser', width: 176, height: 166, columns: 3, x: 62, y: 17, inventoryLabel: 72, player: { main: 84, hotbar: 142 } },
  dropper: { texture: 'gui/container/dispenser', width: 176, height: 166, columns: 3, x: 62, y: 17, inventoryLabel: 72, player: { main: 84, hotbar: 142 } },
}

export function geometry(menu) {
  const type = String(menu.type ?? 'chest').toLowerCase()
  if (KINDS[type]) {
    const kind = KINDS[type]
    return { ...kind, rows: Math.ceil(containerSlotCount(menu) / kind.columns), chest: false }
  }
  const rows = Math.max(1, Math.ceil(containerSlotCount(menu) / 9))
  return { texture: 'gui/container/generic_54', width: 176, height: 114 + rows * 18, columns: 9, x: 8, y: 18, rows, chest: true, inventoryLabel: 20 + rows * 18, player: { main: 103 + (rows - 4) * 18, hotbar: 161 + (rows - 4) * 18 } }
}

export class WindowView {
  /** @param {import('./text.js').Text} text @param {import('./assets.js').Assets} assets */
  constructor(text, assets) {
    this.text = text
    this.assets = assets
  }

  draw(menu, { scale = 3, ratio = 1, margin = 0, slots = false } = {}) {
    const g = geometry(menu)
    const SCALE = scale
    const m = { x: (typeof margin === 'number' ? margin : margin.x) * SCALE, y: (typeof margin === 'number' ? margin : margin.y) * SCALE }
    const canvas = document.createElement('canvas')
    canvas.className = 'window-canvas'
    canvas.width = g.width * SCALE + 2 * m.x
    canvas.height = g.height * SCALE + 2 * m.y
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

export function playerSlot(g, index) {
  if (index >= 27) return { x: 8 + (index - 27) * 18, y: g.player.hotbar }
  return { x: 8 + (index % 9) * 18, y: g.player.main + Math.floor(index / 9) * 18 }
}

export const INVENTORY_SLOTS = 36

export function inventoryIndex(slot) {
  return slot >= 9 ? slot - 9 : 27 + slot
}
