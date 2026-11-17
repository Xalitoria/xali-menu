import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { Schema } from '../src/schema.js'
import { check, slotCount } from '../src/check.js'

const schema = new Schema(JSON.parse(readFileSync(new URL('../schema.json', import.meta.url))))
const messages = (menu) => check(schema, menu).map((p) => `${p.path.join('.')}: ${p.message}`)

test('a menu that is fine has no problems', () => {
  assert.deepEqual(messages({ title: 'T', size: 27, items: { a: { material: 'stone', slot: 3, click: { any: { actions: [{ type: 'close' }] } } } } }), [])
})

test('reports a missing key, an unknown key and a wrong kind', () => {
  const found = messages({ items: { a: { slot: 1, nope: 1, amount: 'x' } } })
  assert.ok(found.includes("title: 'title' is required"))
  assert.ok(found.includes("items.a.material: 'material' is required"))
  assert.ok(found.includes("items.a.nope: 'nope' is not a key of this"))
  assert.ok(found.includes('items.a.amount: should be a number'))
})

test('reports a type that does not exist, but lets an addon type through', () => {
  const found = messages({ title: 'T', 'on-open': [{ type: 'warp' }, { type: 'myplugin:reward', points: 3 }, { type: 'message' }] })
  assert.deepEqual(found.filter((m) => m.startsWith('on-open')), ["on-open.0.type: 'warp' is not a known action", "on-open.2.text: 'text' is required"])
})

test('reports slots outside the window and layouts that name missing items', () => {
  const found = messages({ title: 'T', size: 9, items: { a: { material: 'stone', slot: 9 }, b: { material: 'stone' } }, legend: { '#': 'gone' } })
  assert.ok(found.some((m) => m.includes('slot 9 is outside')))
  assert.ok(found.some((m) => m.includes("items.b: has no slot")))
  assert.ok(found.some((m) => m.includes("'gone' is not in items")))
})

test('other inventory types have a size of their own', () => {
  assert.equal(slotCount({ type: 'hopper' }), 5)
  assert.equal(slotCount({ size: 27 }), 27)
})

test('an item of the player inventory has a slot from 0 to 35', () => {
  const menu = { title: 'T', size: 9, items: {}, inventory: { fine: { material: 'stone', slot: 35 }, past: { material: 'stone', slot: 36 }, none: { material: 'stone' } } }
  assert.deepEqual(messages(menu), [
    "inventory.past.slot: slot 36 is outside the player's inventory (0 to 35: the hotbar, then the three rows above it)",
    'inventory.none: has no slot, so it is never shown',
  ])
})

test('an item of the window past the menu is told to go in the inventory instead', () => {
  assert.match(messages({ title: 'T', size: 9, items: { a: { material: 'stone', slot: 12 } } })[0], /inventory-slots/)
})
