import { test } from 'node:test'
import assert from 'node:assert/strict'
import { freeId, idProblem, renameItem, removeItem, duplicateItem, firstFreeSlot, isCustomMaterial } from '../src/items.js'

const menu = () => ({ items: { a: { material: 'stone', slot: 0 }, b: { material: 'dirt' }, c: { material: 'sand' } }, matrix: { cells: ['#x'], map: { '#': 'b', x: 'c' } } })

test('an id is free when no item has it', () => {
  assert.equal(freeId({}), 'item1')
  assert.equal(freeId({ item1: {}, item2: {} }), 'item3')
})

test('an id has to be one word and not taken', () => {
  const m = menu()
  assert.equal(idProblem(m, 'new'), null)
  assert.match(idProblem(m, ''), /empty/)
  assert.match(idProblem(m, 'two words'), /spaces/)
  assert.match(idProblem(m, 'a'), /another item/i)
  assert.equal(idProblem(m, 'a', 'a'), null, 'its own id is fine')
})

test('renaming keeps the order of the items and follows the matrix map', () => {
  const m = renameItem(menu(), 'b', 'filler')
  assert.deepEqual(Object.keys(m.items), ['a', 'filler', 'c'])
  assert.equal(m.matrix.map['#'], 'filler')
  assert.equal(m.matrix.map.x, 'c')
})

test('a rename that is not allowed changes nothing', () => {
  assert.equal(renameItem(menu(), 'b', 'a'), null)
  assert.equal(renameItem(menu(), 'b', 'b'), null)
  assert.equal(renameItem(menu(), 'nope', 'x'), null)
  assert.equal(renameItem(menu(), 'b', ''), null)
})

test('removing an item takes its matrix map characters with it', () => {
  const m = removeItem(menu(), 'b')
  assert.deepEqual(Object.keys(m.items), ['a', 'c'])
  assert.deepEqual(m.matrix.map, { x: 'c' })
  assert.equal(removeItem(menu(), 'zzz'), null)
})

test('the first free slot skips the claimed ones', () => {
  assert.equal(firstFreeSlot(new Set([0, 1, 3]), 9), 2)
  assert.equal(firstFreeSlot(new Set([0, 1]), 2), null)
})

test('a copy goes right after the item and takes a free slot', () => {
  const m = duplicateItem(menu(), 'a', 'a2', 5)
  assert.deepEqual(Object.keys(m.items), ['a', 'a2', 'b', 'c'])
  assert.equal(m.items.a2.slot, 5)
  assert.equal(m.items.a.slot, 0)
  assert.equal(duplicateItem(menu(), 'a', 'b'), null)
})

test('a material with a prefix of a plugin is a custom item; heads, skins and the game are not', () => {
  for (const material of ['nexo:ruby', 'itemsadder:ruby_sword', 'oraxen:thing']) assert.equal(isCustomMaterial(material), true, material)
  for (const material of ['stone', 'minecraft:stone', 'head:Notch', 'texture:abc', 'basehead:abc', '', undefined, '%var%:x']) assert.equal(isCustomMaterial(material), false, String(material))
})

test('copying an item leaves the copy without the extra slots, in the slot given', () => {
  const m = menu()
  m.items.a['inventory-slots'] = [9]
  m.items.a.slots = [5]
  duplicateItem(m, 'a', 'a-2', 7)
  assert.equal(m.items['a-2'].slot, 7)
  assert.equal('slots' in m.items['a-2'], false)
  assert.equal('inventory-slots' in m.items['a-2'], false)
  assert.deepEqual(m.items.a['inventory-slots'], [9])
})
