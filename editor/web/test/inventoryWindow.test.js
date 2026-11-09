import { test } from 'node:test'
import assert from 'node:assert/strict'
import { inventoryIndex, playerSlot, geometry, INVENTORY_SLOTS } from '../src/window.js'

test('the slots of the player inventory sit where the game has them: the rows above the hotbar', () => {
  const g = geometry({ size: 27 })
  assert.equal(INVENTORY_SLOTS, 36)
  // slot 9 is the first of the rows, slot 35 the last; 0 to 8 are the hotbar, left to right
  assert.deepEqual(playerSlot(g, inventoryIndex(9)), { x: 8, y: g.player.main })
  assert.deepEqual(playerSlot(g, inventoryIndex(35)), { x: 8 + 8 * 18, y: g.player.main + 2 * 18 })
  assert.deepEqual(playerSlot(g, inventoryIndex(0)), { x: 8, y: g.player.hotbar })
  assert.deepEqual(playerSlot(g, inventoryIndex(8)), { x: 8 + 8 * 18, y: g.player.hotbar })
})

test('every slot of the player inventory has a place of its own', () => {
  const g = geometry({ size: 9 })
  const places = new Set(Array.from({ length: INVENTORY_SLOTS }, (_, slot) => JSON.stringify(playerSlot(g, inventoryIndex(slot)))))
  assert.equal(places.size, 36)
})
