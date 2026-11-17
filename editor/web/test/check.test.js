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
  assert.ok(found.includes("items.a.nope: 'nope' is not a key of an item, so it is ignored"))
  assert.ok(found.includes('items.a.amount: should be a number, or a placeholder such as %player_level% that gives one'))
})

test('reports a type that does not exist, but lets an addon type through', () => {
  const found = messages({ title: 'T', 'on-open': [{ type: 'warp' }, { type: 'myplugin:reward', points: 3 }, { type: 'message' }] })
  assert.deepEqual(found.filter((m) => m.startsWith('on-open')), ["on-open.0.type: 'warp' is not an action (see the list in the README)", "on-open.2.text: 'text' is required"])
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

test('an inventory slot of an item is from 0 to 35, and an item with only those has a slot', () => {
  const menu = { title: 'T', size: 9, items: { fine: { material: 'stone', 'inventory-slots': [0, 35] }, past: { material: 'stone', 'inventory-slots': [36] }, none: { material: 'stone' } } }
  assert.deepEqual(messages(menu), [
    "items.past.inventory-slots.0: should be from 0 to 35, not 36",
    'items.none: has no slot, so it is never shown',
  ])
})

test('an item of the menu past its size is told to use inventory-slots', () => {
  assert.match(messages({ title: 'T', size: 9, items: { a: { material: 'stone', slot: 12 } } })[0], /inventory-slots/)
})

test('a number or a switch may be text with a placeholder, and any other text is told', () => {
  const problems = (item) => check(schema, { title: 't', items: { a: { material: 'stone', slot: 0, ...item } } }).map((p) => p.path.at(-1))
  assert.deepEqual(problems({ amount: 3, glow: true, damage: '%player_level%', unbreakable: '%op%' }), [])
  assert.deepEqual(problems({ amount: '5' }), [], 'text of a number is a number')
  assert.deepEqual(problems({ amount: 'many' }), ['amount'])
  assert.deepEqual(problems({ glow: 'maybe' }), ['glow'])
  assert.deepEqual(problems({ amount: 1.5 }), ['amount'])
})

test('a mistyped key, type or word says what it was probably meant to be, and the key is given for a one-click fix', () => {
  const found = check(schema, { title: 'T', items: { a: { material: 'stone', slott: 1, rarity: 'EPICC' } }, 'on-open': [{ type: 'mesage', text: 'hi' }] })
  const by = (key) => found.find((p) => p.path.at(-1) === key)
  assert.equal(by('slott').message, "'slott' is not a key of an item, so it is ignored (did you mean 'slot'?)")
  assert.equal(by('slott').fix, 'slot')
  assert.match(by('rarity').message, /did you mean 'EPIC'\?/)
  assert.match(by('type').message, /did you mean 'message'\?/)
  assert.equal(by('type').fix, 'message')
  assert.ok(check(schema, { title: 'T', items: { a: { material: 'stone', max_stack_size: 2, slot: 1 } } }).some((p) => p.fix === 'max-stack-size'), 'the style of another config')
})

test('what the plugin reads in an easier way is not a problem: one value for a list, an enum in any case, numbers and switches as text', () => {
  const found = check(schema, { title: 'T', refresh: '5s', items: { a: { material: 'stone', slots: 5, rarity: 'epic', slot: '3', glow: 'true', lore: 'one line', click: { left: { actions: { type: 'close' } } } } } })
  assert.deepEqual(found, [])
})

test('a time must be one the plugin reads', () => {
  const bad = (refresh) => check(schema, { title: 'T', refresh }).map((p) => p.path.join('.'))
  assert.deepEqual(bad('soon'), ['refresh'])
  for (const ok of ['5s', '500ms', '1m 30s', '2h', 'PT5S', 1.5 + 's']) assert.deepEqual(bad(ok), [], ok)
})

test('a number outside the limits the model gives it is told in the words of the plugin, for a number, a dynamic number and each entry of a list', () => {
  const found = (item) => check(schema, { title: 'T', size: 27, items: { a: { material: 'stone', slot: 1, ...item } } }).map((p) => `${p.path.slice(2).join('.')}: ${p.message}`)
  assert.deepEqual(found({ amount: 0 }), ['amount: should be from 1 to 99, not 0'])
  assert.deepEqual(found({ amount: '%level%', damage: 5 }), [])
  assert.deepEqual(found({ 'max-stack-size': 100 }), ['max-stack-size: should be from 1 to 99, not 100'])
  assert.deepEqual(found({ slots: [2, -1] }), ['slots.1: can\'t be below 0, not -1'])
  assert.ok(check(schema, { title: 'T', size: 100 }).some((p) => p.path[0] === 'size' && p.message === 'should be from 9 to 54, not 100'))
})
