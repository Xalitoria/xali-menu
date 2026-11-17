import { test } from 'node:test'
import assert from 'node:assert/strict'
import { listedSlots, toggleSlot, interiorSlots, defaultPages, sampleValue, sampleElements, fillText, previewMenu, previewTotal, previewPages, isPaged, ELEMENT, EMPTY } from '../src/pages.js'

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
    pages: { catalog: { type: 'players' }, slots: [10, 11], item: { material: 'head:{name}', name: '&e{name}', lore: ['#{index} of {total}'], require: { require: [] }, slot: 0 } },
    items: { info: { material: 'paper', slot: 10, name: 'Page {page}' } },
  }
  const shown = previewMenu(menu, { name: 'Notch' })
  assert.equal(shown.title, 'Players 1/2')
  assert.deepEqual(Object.keys(shown.items), ['info', `${ELEMENT}1`, `${ELEMENT}2`])
  const [a, b] = [shown.items[`${ELEMENT}1`], shown.items[`${ELEMENT}2`]]
  assert.deepEqual([a.material, a.name, a.lore, a.slot], ['head:Notch', '&eNotch', ['#1 of 3'], 10])
  assert.deepEqual([b.material, b.slot], ['head:Notch2', 11])
  assert.equal(a.require, undefined, 'the gate is left out')
  assert.ok(a.priority < (shown.items.info.priority ?? 0), 'the template is drawn in front')
  assert.equal(shown.items.info.name, 'Page 1')
  assert.equal(menu.items.info.name, 'Page {page}', 'the menu itself is not changed')
  assert.equal(isPaged(`${ELEMENT}1`), true)
  assert.equal(isPaged('info'), false)
})

test('the preview pretends there are a page and a half of elements', () => {
  assert.equal(previewTotal(2), 3)
  assert.equal(previewTotal(7), 11)
  assert.equal(previewTotal(1), 2)
})

test('the preview turns to the second page, which is half full, and clamps the page asked for', () => {
  const menu = {
    title: '{page}/{pages} {page_prev}<>{page_next}',
    pages: { catalog: { type: 'players' }, slots: [10, 11, 12, 13], item: { material: 'paper', name: '{name} #{index}' } },
    items: { under: { material: 'stone', slot: 13 } },
  }
  const second = previewMenu(menu, { name: 'Notch' }, { page: 2 })
  assert.equal(second.title, '2/2 1<>2')
  assert.deepEqual(Object.keys(second.items), ['under', `${ELEMENT}1`, `${ELEMENT}2`], 'two of six elements are on the second page')
  assert.deepEqual([second.items[`${ELEMENT}1`].name, second.items[`${ELEMENT}1`].slot], ['Notch5 #5', 10])
  assert.deepEqual([second.items[`${ELEMENT}2`].name, second.items[`${ELEMENT}2`].slot], ['Notch6 #6', 11])
  assert.equal(previewMenu(menu, { name: 'Notch' }, { page: 9 }).title, '2/2 1<>2', 'beyond the last page is the last')
  assert.equal(previewMenu(menu, { name: 'Notch' }, { page: 0 }).title, '1/2 1<>2')
})

test('real elements from the server take the place of the examples, and decide the pages', () => {
  const menu = { title: '{page}/{pages} of {total}', pages: { catalog: { type: 'players' }, slots: [0, 1], item: { material: 'paper', name: '{name} {index}' } }, items: {} }
  const elements = [{ name: 'Alex', index: '1' }, { name: 'Steve', index: '2' }, { name: 'Zed', index: '3' }]
  const first = previewMenu(menu, { name: 'Notch' }, { elements })
  assert.equal(first.title, '1/2 of 3')
  assert.deepEqual([first.items[`${ELEMENT}1`].name, first.items[`${ELEMENT}2`].name], ['Alex 1', 'Steve 2'])
  const second = previewMenu(menu, { name: 'Notch' }, { elements, page: 2 })
  assert.deepEqual(Object.keys(second.items), [`${ELEMENT}1`])
  assert.equal(second.items[`${ELEMENT}1`].name, 'Zed 3')
  assert.equal(previewMenu(menu, { name: 'Notch' }, { elements: [] }).title, '1/2 of 3', 'no elements from the server: the examples again')
  assert.equal(previewPages(2, elements), 2)
  assert.equal(previewPages(2, [{ index: '1' }]), 1)
  assert.equal(previewPages(2, []), 2)
  assert.equal(previewPages(2, null), 2)
  assert.deepEqual(Object.keys(previewMenu(menu, {}, { elements, empty: true }).items), [], 'the empty page is empty even with elements from the server')
})

test('the page of no elements has only the empty item, in the first slot', () => {
  const menu = {
    title: '{page}/{pages} of {total}',
    pages: { catalog: { type: 'players' }, slots: [11, 10], item: { material: 'paper' }, empty: { material: 'barrier', name: 'Nobody', require: { require: [] } } },
    items: {},
  }
  const shown = previewMenu(menu, { name: 'Notch' }, { empty: true })
  assert.equal(shown.title, '1/1 of 0')
  assert.deepEqual(Object.keys(shown.items), [EMPTY])
  assert.deepEqual([shown.items[EMPTY].material, shown.items[EMPTY].name, shown.items[EMPTY].slot, shown.items[EMPTY].require], ['barrier', 'Nobody', 11, undefined])
  const without = previewMenu({ ...menu, pages: { ...menu.pages, empty: undefined } }, {}, { empty: true })
  assert.deepEqual(Object.keys(without.items), [], 'no empty item, nothing drawn')
})

test('a menu that is not paged, or has no slot for elements, is shown as it is', () => {
  const plain = { title: 'x', items: {} }
  assert.equal(previewMenu(plain, {}), plain)
  const empty = { pages: { catalog: { type: 'players' }, slots: [], item: { material: 'stone' } } }
  assert.equal(previewMenu(empty, {}), empty)
})
