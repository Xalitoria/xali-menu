import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { Schema } from '../src/schema.js'
import { normalize } from '../src/normalize.js'
import { Store } from '../src/store.js'

const schema = new Schema(JSON.parse(readFileSync(new URL('../schema.json', import.meta.url))))

test('a single value becomes a list, an enum takes the model\'s case, text of a number is a number, and a placeholder is left as it is', () => {
  const menu = normalize(schema, { title: 'T', items: { a: { material: 'stone', slots: 5, lore: 'one', rarity: 'epic', slot: '3', glow: 'true', amount: '%x%', click: { left: { actions: { type: 'close' } } } } } })
  const { a } = menu.items
  assert.deepEqual([a.slots, a.lore, a.click.left.actions], [[5], ['one'], [{ type: 'close' }]])
  assert.deepEqual([a.rarity, a.slot, a.glow, a.amount], ['EPIC', 3, 'true', '%x%'])
})

test('what the schema does not know and a menu that is fine are left alone, and the store writes what it loads and changes the one way', () => {
  const menu = { title: 'T', size: 27, items: { a: { material: 'stone', slot: 1, mystery: 'x' } }, 'on-open': [{ type: 'myplugin:thing', n: '5' }] }
  assert.deepEqual(normalize(schema, menu), menu)
  const store = new Store({})
  store.normalizer = (m) => normalize(schema, m)
  store.load({ title: 'T', items: { a: { material: 'stone', slots: 5 } } })
  store.set(['items', 'a', 'lore'], 'x')
  assert.deepEqual([store.value.items.a.slots, store.value.items.a.lore], [[5], ['x']])
})
