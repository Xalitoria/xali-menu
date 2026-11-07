import { test } from 'node:test'
import assert from 'node:assert/strict'
import { listedSlots, toggleSlot, interiorSlots, defaultPages, sampleValue, sampleElements, fillText, previewMenu, isPaged, ELEMENT } from '../src/pages.js'

test('the slots of the elements are the slots, or the cells of the layout', () => {
  assert.deepEqual(listedSlots({ slots: [3, 1] }), [3, 1])
  assert.deepEqual(listedSlots({ layout: ['#########', '#xx   xx#', 'x'] }), [10, 11, 15, 16, 18])
  assert.deepEqual(listedSlots({ layout: ['ooo'], cell: 'o' }), [0, 1, 2])
  assert.deepEqual(listedSlots(undefined), [])
})

test('toggling a slot adds it in order or takes it out', () => {
  assert.deepEqual(toggleSlot({ slots: [1, 5] }, 3).slots, [1, 3, 5])
  assert.deepEqual(toggleSlot({ slots: [1, 3, 5] }, 3).slots, [1, 5])
  const before = { slots: [1] }
  toggleSlot(before, 2)
  assert.deepEqual(before.slots, [1], 'the pages given are not changed')
})

test('toggling a slot of a layout changes its character, and makes rows it lacks', () => {
  assert.deepEqual(toggleSlot({ layout: ['#########'] }, 4).layout, ['####x####'])
  assert.deepEqual(toggleSlot({ layout: ['####x####'] }, 4).layout, ['#### ####'])
  assert.deepEqual(toggleSlot({ layout: ['####x'] }, 4).layout, ['####'])
  assert.deepEqual(toggleSlot({ layout: ['#'], cell: 'o' }, 10).layout, ['#', ' o'])
})

test('the interior of a chest leaves the border, and a small one is all of it', () => {
  assert.deepEqual(interiorSlots(27), [10, 11, 12, 13, 14, 15, 16])
  assert.equal(interiorSlots(54).length, 28)
  assert.deepEqual(interiorSlots(18), Array.from({ length: 18 }, (_, i) => i))
  assert.deepEqual(interiorSlots(5, 5), [0, 1, 2, 3, 4])
  assert.deepEqual(defaultPages(27).slots, interiorSlots(27))
})

test('example values: numbers count on, text gets a number', () => {
  assert.equal(sampleValue('1', 3), '3')
  assert.equal(sampleValue('Notch', 1), 'Notch')
  assert.equal(sampleValue('Notch', 2), 'Notch2')
  assert.deepEqual(sampleElements({ name: 'Notch' }, 2), [{ name: 'Notch', index: '1' }, { name: 'Notch2', index: '2' }])
  assert.deepEqual(sampleElements(undefined, 1), [{ index: '1' }])
})

test('only the names that are known are filled in', () => {
  assert.equal(fillText('head:{name} {unknown} {page}', { name: 'Notch' }), 'head:Notch {unknown} {page}')
})

test('the preview of a paged menu draws the template in each slot of the first page', () => {
  const menu = {
    title: 'Players {page}/{pages}',
    pages: { catalog: { type: 'players' }, slots: [10, 11], item: { material: 'head:{name}', name: '&e{name}', lore: ['#{index} of {total}'], view: { require: [] }, slot: 0 } },
    items: { info: { material: 'paper', slot: 10, name: 'Page {page}' } },
  }
  const shown = previewMenu(menu, { name: 'Notch' })
  assert.equal(shown.title, 'Players 1/2')
  assert.deepEqual(Object.keys(shown.items), ['info', `${ELEMENT}1`, `${ELEMENT}2`])
  const [a, b] = [shown.items[`${ELEMENT}1`], shown.items[`${ELEMENT}2`]]
  assert.deepEqual([a.material, a.name, a.lore, a.slot], ['head:Notch', '&eNotch', ['#1 of 4'], 10])
  assert.deepEqual([b.material, b.slot], ['head:Notch2', 11])
  assert.equal(a.view, undefined, 'the gate is left out')
  assert.ok(a.priority < (shown.items.info.priority ?? 0), 'the template is drawn in front')
  assert.equal(shown.items.info.name, 'Page 1')
  assert.equal(menu.items.info.name, 'Page {page}', 'the menu itself is not changed')
  assert.equal(isPaged(`${ELEMENT}1`), true)
  assert.equal(isPaged('info'), false)
})

test('a menu that is not paged, or has no slot for elements, is shown as it is', () => {
  const plain = { title: 'x', items: {} }
  assert.equal(previewMenu(plain, {}), plain)
  const empty = { pages: { catalog: { type: 'players' }, slots: [], item: { material: 'stone' } } }
  assert.equal(previewMenu(empty, {}), empty)
})
