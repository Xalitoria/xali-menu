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

test('reports slots outside the window and a matrix that breaks its rules or names missing items', () => {
  const found = messages({ title: 'T', size: 9, items: { a: { material: 'stone', slot: 9 }, b: { material: 'stone' } }, matrix: { cells: ['0123456789', '#'], map: { '#': 'gone', ab: 'b' } } })
  assert.ok(found.some((m) => m.includes('slot 9 is outside')))
  assert.ok(found.some((m) => m.includes("items.b: has no slot")))
  assert.ok(found.some((m) => m.includes("'gone' is not in items")))
  assert.ok(found.some((m) => m.includes('more than 9 characters')))
  assert.ok(found.some((m) => m.includes('more than the menu')))
  assert.ok(found.some((m) => m.includes("single characters, not 'ab'")))
})

test('an item placed through the global menu matrix needs no slot of its own, and renamed/moved keys say what replaced them', () => {
  assert.deepEqual(messages({ title: 'T', size: 9, matrix: { cells: ['#########'], map: { '#': 'border' } }, items: { border: { material: 'stone' } } }), [])
  const bad = messages({ title: 'T', size: 9, layout: ['x'], legend: { x: 'a' }, items: { a: { material: 'stone', slot: 0, matrix: ['x'], 'dynamic-amount': '2' } } })
  assert.ok(bad.some((m) => m.includes("'matrix' was moved to the menu-level 'matrix { cells, map }'")))
  assert.ok(bad.some((m) => m.includes("'layout' was replaced by 'matrix.cells'")))
  assert.ok(bad.some((m) => m.includes("'legend' was replaced by 'matrix.map'")))
  assert.ok(bad.some((m) => m.includes("'dynamic-amount' was merged into 'amount'")))
})

test('decimal limits from @WithinRange are checked in actions and catalogs', () => {
  const sound = messages({ title: 'T', items: { a: { material: 'stone', slot: 0, click: { any: { actions: [{ type: 'sound', sound: 'ui.button.click', pitch: 5 }] } } } } })
  assert.ok(sound.some((m) => m.includes('should be from 0.5 to 2, not 5')), sound.join('; '))
  const bar = messages({ title: 'T', items: { a: { material: 'stone', slot: 0, click: { any: { actions: [{ type: 'boss-bar', text: 'hi', progress: 1.5 }] } } } } })
  assert.ok(bar.some((m) => m.includes('should be from 0 to 1, not 1.5')), bar.join('; '))
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

test('the numbers of an action or a condition have their limits too', () => {
  const messagesOf = (menu) => messages({ title: 'T', items: { a: { material: 'stone', slot: 1, ...menu } } })
  assert.ok(messagesOf({ click: { left: { actions: [{ type: 'chance', percent: 150, action: { type: 'close' } }] } } })
    .includes('items.a.click.left.actions.0.percent: should be from 0 to 100, not 150'))
  assert.ok(messagesOf({ click: { left: { actions: [{ type: 'potion', effect: 'speed', amplifier: 300 }] } } })
    .includes('items.a.click.left.actions.0.amplifier: should be from 0 to 255, not 300'))
  assert.deepEqual(messagesOf({ click: { left: { actions: [{ type: 'delay', ticks: 20, action: { type: 'close' } }] } } }), [])
})

test('a material that the game or the server\'s plugin does not have is told, with the one it was meant to be; what the editor has no list of is left alone', async () => {
  const { knownItems, entries } = await import('../src/catalog.js')
  const vanilla = Array.from({ length: 600 }, (_, i) => `item_${i}`).concat(['diamond', 'diamond_sword'])
  const known = knownItems(entries(vanilla, { nexo: ['earth_wand', 'dark_wand'] }))
  const items = { a: { material: 'diamnd' }, b: { material: 'nexo:earth_wnd' }, c: { material: 'minecraft:DIAMOND_SWORD' }, d: { material: 'nexo:dark_wand' }, e: { material: 'head:Steve' },
    f: { material: 'itemsadder:ruby' }, g: { material: '%player_item%' }, h: { material: 'nexo:wands:earth_wand' } }
  const found = check(schema, { title: 'T', items }, known).filter((p) => p.path[2] === 'material').map((p) => `${p.path[1]}: ${p.message}`)
  assert.deepEqual(found, ["a: 'diamnd' isn't an item of the game (did you mean 'diamond'?)", "b: 'nexo:earth_wnd' isn't an item of nexo (did you mean 'earth_wand'?)"])
  assert.equal(knownItems(entries(['diamond'], {})), null, 'a short list is not taken for all the items')
  assert.deepEqual(check(schema, { title: 'T', items }, null).filter((p) => p.path[2] === 'material'), [], 'with no list nothing is said')
})
