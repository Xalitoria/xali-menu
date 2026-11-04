import { test } from 'node:test'
import assert from 'node:assert/strict'
import { entries, search } from '../src/catalog.js'

const lang = { 'item.minecraft.diamond_sword': 'Diamond Sword', 'item.minecraft.iron_sword': 'Iron Sword', 'block.minecraft.stone': 'Stone', 'item.minecraft.stone_axe': 'Stone Axe', 'block.minecraft.cobblestone': 'Cobblestone' }
const list = entries(['cobblestone', 'diamond_sword', 'iron_sword', 'stone', 'stone_axe', 'oak_planks'], { nexo: ['ruby', 'ruby_sword'], itemsadder: ['pack:gem'] }, lang)

test('entries have names from the language file, a made-up name otherwise, and the prefix for custom items', () => {
  assert.equal(list.find((e) => e.id === 'oak_planks').name, 'Oak Planks')
  assert.equal(list.find((e) => e.id === 'stone').name, 'Stone')
  const ruby = list.find((e) => e.id === 'nexo:ruby')
  assert.deepEqual([ruby.kind, ruby.source, ruby.name], ['custom', 'nexo', 'ruby'])
  assert.equal(list.find((e) => e.id === 'itemsadder:pack:gem').source, 'itemsadder')
})

test('every word has to match, in the id or the name', () => {
  assert.deepEqual(search(list, 'sword').map((e) => e.id), ['diamond_sword', 'iron_sword', 'nexo:ruby_sword'])
  assert.deepEqual(search(list, 'iron sword').map((e) => e.id), ['iron_sword'])
  assert.deepEqual(search(list, 'diamond_sw').map((e) => e.id), ['diamond_sword'])
  assert.deepEqual(search(list, 'zzz'), [])
})

test('the exact id comes first, then what starts with the query, then the rest', () => {
  assert.deepEqual(search(list, 'stone').map((e) => e.id).slice(0, 3), ['stone', 'stone_axe', 'cobblestone'])
  assert.equal(search(list, 'minecraft:stone')[0].id, 'stone')
  assert.equal(search(list, 'nexo:')[0].id, 'nexo:ruby')
})

test('an empty query lists from the start, up to the limit', () => {
  assert.equal(search(list, '', 3).length, 3)
  assert.equal(search(list, '   ', 100).length, list.length)
})
