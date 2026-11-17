import { test } from 'node:test'
import assert from 'node:assert/strict'
import { claimKey, claimsOf, setClaims, addClaim, removeClaim, moveClaim, moveToItems, placesOf, inventoryEntries } from '../src/placement.js'

const menu = () => ({
  title: 'T', size: 27, layout: ['#########'], legend: { '#': 'fill' },
  items: { fill: { material: 'glass_pane', slot: 9, slots: [10, 11] }, ok: { material: 'lime_wool', slot: 13 } },
  inventory: { coins: { material: 'gold_ingot', slot: 9 }, back: { material: 'arrow', slots: [0, 1] } },
})

test('an item of items has its inventory slots under their own key', () => {
  assert.equal(claimKey('items', 'items'), 'slot')
  assert.equal(claimKey('items', 'inventory'), 'inventory-slots')
  assert.equal(claimKey('inventory', 'inventory'), 'slot')
  assert.equal(claimKey('inventory', 'items'), null)
})

test('the claims are read in order without repeats', () => {
  assert.deepEqual(claimsOf({ slot: 3, slots: [4, 3, 5] }, 'items', 'items'), [3, 4, 5])
  assert.deepEqual(claimsOf({ slots: [1] }, 'inventory', 'inventory'), [1])
  assert.deepEqual(claimsOf({ 'inventory-slots': [9, 10] }, 'items', 'inventory'), [9, 10])
  assert.deepEqual(claimsOf({ slot: 3 }, 'inventory', 'items'), [])
})

test('claims are written as a slot and the rest as slots', () => {
  assert.deepEqual(setClaims({ material: 'x', slot: 1, slots: [2] }, 'items', 'items', [5, 6, 7]), { material: 'x', slot: 5, slots: [6, 7] })
  assert.deepEqual(setClaims({ material: 'x', slot: 1, slots: [2] }, 'items', 'items', [5]), { material: 'x', slot: 5 })
  assert.deepEqual(setClaims({ material: 'x', slot: 1 }, 'items', 'items', []), { material: 'x' })
  assert.deepEqual(setClaims({ material: 'x' }, 'items', 'inventory', [9]), { material: 'x', 'inventory-slots': [9] })
  assert.deepEqual(setClaims({ material: 'x', 'inventory-slots': [9] }, 'items', 'inventory', []), { material: 'x' })
})

test('an item is added to more slots of both areas', () => {
  const m = menu()
  assert.ok(addClaim(m, 'ok', 'items', 'items', 14))
  assert.ok(addClaim(m, 'ok', 'items', 'inventory', 20))
  assert.deepEqual(m.items.ok, { material: 'lime_wool', slot: 13, slots: [14], 'inventory-slots': [20] })
  assert.equal(addClaim(m, 'ok', 'items', 'items', 14), null)
  assert.equal(addClaim(m, 'ok', 'items', 'items', -1), null)
  assert.equal(addClaim(m, 'missing', 'items', 'items', 1), null)
  // an item of inventory is only in the player's inventory
  assert.equal(addClaim(m, 'coins', 'inventory', 'items', 3), null)
})

test('removing a slot promotes the next', () => {
  const m = menu()
  assert.ok(removeClaim(m, 'fill', 'items', 'items', 9))
  assert.deepEqual(m.items.fill, { material: 'glass_pane', slot: 10, slots: [11] })
  assert.equal(removeClaim(m, 'fill', 'items', 'items', 99), null)
  // layout cells are not the item's own
  assert.equal(removeClaim(m, 'fill', 'items', 'items', 0), null)
})

test('moving takes the item from a slot, copying keeps it there', () => {
  const m = menu()
  assert.ok(moveClaim(m, 'fill', 'items', { area: 'items', slot: 10 }, { area: 'items', slot: 20 }))
  assert.deepEqual(claimsOf(m.items.fill, 'items', 'items'), [9, 11, 20])
  assert.ok(moveClaim(m, 'fill', 'items', { area: 'items', slot: 9 }, { area: 'inventory', slot: 30 }, true))
  assert.deepEqual(claimsOf(m.items.fill, 'items', 'items'), [9, 11, 20])
  assert.deepEqual(claimsOf(m.items.fill, 'items', 'inventory'), [30])
  assert.ok(moveClaim(m, 'fill', 'items', { area: 'items', slot: 9 }, { area: 'inventory', slot: 31 }))
  assert.deepEqual(claimsOf(m.items.fill, 'items', 'items'), [11, 20])
  assert.deepEqual(claimsOf(m.items.fill, 'items', 'inventory'), [30, 31])
})

test('moving onto its own slot, or onto a slot it is in, changes nothing but what it leaves', () => {
  const m = menu()
  assert.equal(moveClaim(m, 'fill', 'items', { area: 'items', slot: 9 }, { area: 'items', slot: 9 }), null)
  assert.equal(moveClaim(m, 'fill', 'items', { area: 'items', slot: 20 }, { area: 'items', slot: 10 }, true), null)
  // moved onto another of its slots, it only leaves the first
  assert.ok(moveClaim(m, 'fill', 'items', { area: 'items', slot: 9 }, { area: 'items', slot: 10 }))
  assert.deepEqual(claimsOf(m.items.fill, 'items', 'items'), [10, 11])
})

test('an item dragged from a slot the layout gives it is only added', () => {
  const m = menu()
  assert.ok(moveClaim(m, 'fill', 'items', { area: 'items', slot: 0 }, { area: 'items', slot: 25 }))
  assert.deepEqual(claimsOf(m.items.fill, 'items', 'items'), [9, 10, 11, 25])
})

test('an item of the inventory list moves to items to be used in the window', () => {
  const m = menu()
  assert.equal(moveToItems(m, 'back'), 'back')
  assert.equal('back' in m.inventory, false)
  assert.deepEqual(m.items.back, { material: 'arrow', 'inventory-slots': [0, 1] })
  assert.ok(addClaim(m, 'back', 'items', 'items', 4))
  assert.equal(moveToItems(m, 'nothing'), null)
})

test('moving to items keeps another item of that id, and drops an emptied list', () => {
  const m = { items: { coins: { material: 'stone', slot: 1 } }, inventory: { coins: { material: 'gold_ingot', slot: 9 } } }
  assert.equal(moveToItems(m, 'coins'), 'coins-1')
  assert.equal('inventory' in m, false)
  assert.deepEqual(m.items['coins-1'], { material: 'gold_ingot', 'inventory-slots': [9] })
  assert.deepEqual(m.items.coins, { material: 'stone', slot: 1 })
})

test('every place of an item', () => {
  const m = menu()
  addClaim(m, 'fill', 'items', 'inventory', 5)
  assert.deepEqual(placesOf(m, 'fill'), { items: [9, 10, 11], inventory: [5], layout: [0, 1, 2, 3, 4, 5, 6, 7, 8] })
  assert.deepEqual(placesOf(m, 'back', 'inventory'), { items: [], inventory: [0, 1], layout: [] })
})

test('the player rows hold the items of both lists by priority', () => {
  const m = menu()
  addClaim(m, 'ok', 'items', 'inventory', 9)
  m.items.ok.priority = -1
  const at = inventoryEntries(m)
  assert.deepEqual(at.get(9), [{ name: 'ok', section: 'items' }, { name: 'coins', section: 'inventory' }])
  assert.deepEqual(at.get(0), [{ name: 'back', section: 'inventory' }])
})
