import { test } from 'node:test'
import assert from 'node:assert/strict'
import { claimsOf, setClaims, addClaim, removeClaim, moveClaim, placesOf, inventoryEntries } from '../src/placement.js'

const menu = () => ({
  title: 'T', size: 27, layout: ['#########'], legend: { '#': 'fill' },
  items: { fill: { material: 'glass_pane', slot: 9, slots: [10, 11] }, ok: { material: 'lime_wool', slot: 13 } },
})

test('the claims are read in order without repeats', () => {
  assert.deepEqual(claimsOf({ slot: 3, slots: [4, 3, 5] }, 'items'), [3, 4, 5])
  assert.deepEqual(claimsOf({ slots: [1] }, 'items'), [1])
  assert.deepEqual(claimsOf({ 'inventory-slots': [9, 10] }, 'inventory'), [9, 10])
  assert.deepEqual(claimsOf({ slot: 3 }, 'inventory'), [])
  assert.deepEqual(claimsOf(undefined, 'items'), [])
})

test('claims are written as a slot and the rest as slots', () => {
  assert.deepEqual(setClaims({ material: 'x', slot: 1, slots: [2] }, 'items', [5, 6, 7]), { material: 'x', slot: 5, slots: [6, 7] })
  assert.deepEqual(setClaims({ material: 'x', slot: 1, slots: [2] }, 'items', [5]), { material: 'x', slot: 5 })
  assert.deepEqual(setClaims({ material: 'x', slot: 1 }, 'items', []), { material: 'x' })
  assert.deepEqual(setClaims({ material: 'x' }, 'inventory', [9]), { material: 'x', 'inventory-slots': [9] })
  assert.deepEqual(setClaims({ material: 'x', 'inventory-slots': [9] }, 'inventory', []), { material: 'x' })
})

test('an item is added to more slots of both areas', () => {
  const m = menu()
  assert.ok(addClaim(m, 'ok', 'items', 14))
  assert.ok(addClaim(m, 'ok', 'inventory', 20))
  assert.deepEqual(m.items.ok, { material: 'lime_wool', slot: 13, slots: [14], 'inventory-slots': [20] })
  assert.equal(addClaim(m, 'ok', 'items', 14), null)
  assert.equal(addClaim(m, 'ok', 'items', -1), null)
  assert.equal(addClaim(m, 'missing', 'items', 1), null)
})

test('removing a slot promotes the next', () => {
  const m = menu()
  assert.ok(removeClaim(m, 'fill', 'items', 9))
  assert.deepEqual(m.items.fill, { material: 'glass_pane', slot: 10, slots: [11] })
  assert.equal(removeClaim(m, 'fill', 'items', 99), null)
  // layout cells are not the item's own
  assert.equal(removeClaim(m, 'fill', 'items', 0), null)
})

test('moving takes the item from a slot, copying keeps it there', () => {
  const m = menu()
  assert.ok(moveClaim(m, 'fill', { area: 'items', slot: 10 }, { area: 'items', slot: 20 }))
  assert.deepEqual(claimsOf(m.items.fill, 'items'), [9, 11, 20])
  assert.ok(moveClaim(m, 'fill', { area: 'items', slot: 9 }, { area: 'inventory', slot: 30 }, true))
  assert.deepEqual(claimsOf(m.items.fill, 'items'), [9, 11, 20])
  assert.deepEqual(claimsOf(m.items.fill, 'inventory'), [30])
  assert.ok(moveClaim(m, 'fill', { area: 'items', slot: 9 }, { area: 'inventory', slot: 31 }))
  assert.deepEqual(claimsOf(m.items.fill, 'items'), [11, 20])
  assert.deepEqual(claimsOf(m.items.fill, 'inventory'), [30, 31])
})

test('moving onto its own slot, or onto a slot it is in, changes nothing but what it leaves', () => {
  const m = menu()
  assert.equal(moveClaim(m, 'fill', { area: 'items', slot: 9 }, { area: 'items', slot: 9 }), null)
  assert.equal(moveClaim(m, 'fill', { area: 'items', slot: 20 }, { area: 'items', slot: 10 }, true), null)
  // moved onto another of its slots, it only leaves the first
  assert.ok(moveClaim(m, 'fill', { area: 'items', slot: 9 }, { area: 'items', slot: 10 }))
  assert.deepEqual(claimsOf(m.items.fill, 'items'), [10, 11])
})

test('an item dragged from a slot the layout gives it is only added', () => {
  const m = menu()
  assert.ok(moveClaim(m, 'fill', { area: 'items', slot: 0 }, { area: 'items', slot: 25 }))
  assert.deepEqual(claimsOf(m.items.fill, 'items'), [9, 10, 11, 25])
})

test('every place of an item', () => {
  const m = menu()
  addClaim(m, 'fill', 'inventory', 5)
  assert.deepEqual(placesOf(m, 'fill'), { items: [9, 10, 11], inventory: [5], layout: [0, 1, 2, 3, 4, 5, 6, 7, 8] })
  assert.deepEqual(placesOf(m, 'ok'), { items: [13], inventory: [], layout: [] })
})

test('the player rows hold the items there by priority', () => {
  const m = menu()
  addClaim(m, 'ok', 'inventory', 9)
  addClaim(m, 'fill', 'inventory', 9)
  m.items.ok.priority = -1
  assert.deepEqual(inventoryEntries(m).get(9), ['ok', 'fill'])
})
