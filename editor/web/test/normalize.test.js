import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { Schema } from '../src/schema.js'
import { normalize } from '../src/normalize.js'
import { Store } from '../src/store.js'

const schema = new Schema(JSON.parse(readFileSync(new URL('../schema.json', import.meta.url))))

test('a single value where a list is expected becomes a list, in any depth', () => {
  const menu = normalize(schema, { title: 'T', items: { a: { material: 'stone', slots: 5, lore: 'one', click: { left: { actions: { type: 'close' } } } } } })
  assert.deepEqual(menu.items.a.slots, [5])
  assert.deepEqual(menu.items.a.lore, ['one'])
  assert.deepEqual(menu.items.a.click.left.actions, [{ type: 'close' }])
})

test('an enum is written in the case the model has, and numbers and switches as text are what they say', () => {
  const menu = normalize(schema, { title: 'T', items: { a: { material: 'stone', rarity: 'epic', slot: '3', glow: 'true', amount: '%x%' } } })
  assert.equal(menu.items.a.rarity, 'EPIC')
  assert.equal(menu.items.a.slot, 3)
  assert.equal(menu.items.a.glow, 'true', 'a dynamic value is left as it is: it may be a placeholder')
  assert.equal(menu.items.a.amount, '%x%')
})

test('what the schema does not know is left alone, and a menu that is fine is not changed', () => {
  const menu = { title: 'T', size: 27, items: { a: { material: 'stone', slot: 1, mystery: 'x' } }, 'on-open': [{ type: 'myplugin:thing', n: '5' }] }
  assert.deepEqual(normalize(schema, menu), menu)
})

test('the store writes a menu that is loaded or changed the one way', () => {
  const store = new Store({})
  store.normalizer = (m) => normalize(schema, m)
  store.load({ title: 'T', items: { a: { material: 'stone', slots: 5 } } })
  assert.deepEqual(store.value.items.a.slots, [5])
  store.set(['items', 'a', 'lore'], 'x')
  assert.deepEqual(store.value.items.a.lore, ['x'])
})
