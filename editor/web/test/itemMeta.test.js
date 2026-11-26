import test from 'node:test'
import assert from 'node:assert/strict'
import { evaluateItemMeta, isKeyApplicable, resolveItemBase, itemPropertyGroup } from '../src/itemMeta.js'

const ALL_KEYS = [
  'material', 'skull', 'name', 'lore', 'amount', 'slot', 'slots', 'inventory-slots',
  'priority', 'enchants', 'hide', 'unbreakable', 'model', 'color', 'banner', 'base-color',
  'potion', 'potion-type', 'stored-enchants', 'damage', 'item-name', 'rarity', 'glow',
  'max-stack-size', 'hide-tooltip', 'item-model', 'food', 'trim', 'book', 'firework',
  'fish', 'attributes', 'tooltip-style', 'equippable', 'tool', 'nbt', 'update',
  'view', 'click', 'accept', 'bedrock',
]

test('safe mode only allows meta applicable to each vanilla material', () => {
  const stone = evaluateItemMeta({ material: 'stone' }, ALL_KEYS)
  assert.equal(stone.allowed.has('name'), true)
  assert.equal(stone.allowed.has('glow'), true)
  assert.equal(stone.allowed.has('food'), true)
  for (const key of ['skull', 'color', 'banner', 'base-color', 'potion', 'potion-type', 'stored-enchants', 'trim', 'book', 'firework', 'fish', 'damage', 'unbreakable']) {
    assert.equal(stone.allowed.has(key), false, `stone should not allow ${key}`)
  }

  const leather = evaluateItemMeta({ material: 'leather_chestplate' }, ALL_KEYS)
  assert.deepEqual(leather.recommended.sort(), ['color', 'damage', 'trim', 'unbreakable'])
  assert.equal(leather.allowed.has('potion'), false)

  const shield = evaluateItemMeta({ material: 'shield' }, ALL_KEYS)
  assert.deepEqual(shield.recommended.sort(), ['banner', 'base-color', 'damage', 'unbreakable'])

  const banner = evaluateItemMeta({ material: 'red_banner' }, ALL_KEYS)
  assert.deepEqual(banner.recommended, ['banner'])
  assert.equal(banner.allowed.has('base-color'), false)

  const head = evaluateItemMeta({ material: 'player_head' }, ALL_KEYS)
  assert.deepEqual(head.recommended, ['skull'])

  const prefHead = evaluateItemMeta({ material: 'head:Steve' }, ALL_KEYS)
  assert.equal(prefHead.allowed.has('skull'), false, 'head:<player> already specifies the skin in material')

  const potion = evaluateItemMeta({ material: 'splash_potion' }, ALL_KEYS)
  assert.deepEqual(potion.recommended.sort(), ['potion', 'potion-type'])

  const book = evaluateItemMeta({ material: 'written_book' }, ALL_KEYS)
  assert.deepEqual(book.recommended, ['book'])

  const encBook = evaluateItemMeta({ material: 'enchanted_book' }, ALL_KEYS)
  assert.deepEqual(encBook.recommended, ['stored-enchants'])

  const rocket = evaluateItemMeta({ material: 'firework_rocket' }, ALL_KEYS)
  assert.deepEqual(rocket.recommended, ['firework'])

  const fish = evaluateItemMeta({ material: 'tropical_fish_bucket' }, ALL_KEYS)
  assert.deepEqual(fish.recommended, ['fish'])
})

test('custom ItemsAdder and Nexo items inherit applicability from server-resolved base material', () => {
  const resolved = new Map([
    ['itemsadder:my:ruby_sword', { material: 'diamond_sword', maxDamage: 1561 }],
    ['itemsadder:my:wizard_hat', { material: 'leather_helmet', maxDamage: 55 }],
    ['nexo:custom_paper', { material: 'paper', maxDamage: 0 }],
  ])

  const sword = evaluateItemMeta({ material: 'itemsadder:my:ruby_sword' }, ALL_KEYS, { resolved })
  assert.deepEqual(sword.recommended.sort(), ['damage', 'unbreakable'])
  assert.equal(sword.allowed.has('trim'), false)

  const hat = evaluateItemMeta({ material: 'itemsadder:my:wizard_hat' }, ALL_KEYS, { resolved })
  assert.deepEqual(hat.recommended.sort(), ['color', 'damage', 'trim', 'unbreakable'])

  const paper = evaluateItemMeta({ material: 'nexo:custom_paper' }, ALL_KEYS, { resolved })
  assert.deepEqual(paper.recommended, [])
})

test('toggling unsafe mode off preserves non-applicable properties without mutating the item', () => {
  const item = { material: 'stone', name: 'Magic Rock', 'potion-type': 'healing', trim: { pattern: 'coast', material: 'gold' } }
  const snapshot = structuredClone(item)

  const unsafeRes = evaluateItemMeta(item, ALL_KEYS, { unsafe: true })
  assert.equal(unsafeRes.allowed.has('potion-type'), true)
  assert.equal(unsafeRes.allowed.has('trim'), true)
  assert.deepEqual(unsafeRes.hiddenPresent, [])

  const safeRes = evaluateItemMeta(item, ALL_KEYS, { unsafe: false })
  assert.equal(safeRes.allowed.has('potion-type'), false)
  assert.equal(safeRes.allowed.has('trim'), false)
  assert.deepEqual(safeRes.hiddenPresent.sort(), ['potion-type', 'trim'])
  // Item object itself must be completely untouched
  assert.deepEqual(item, snapshot)
})

test('resolveItemBase handles dynamic placeholders and grouping covers known keys', () => {
  assert.deepEqual(resolveItemBase('%player_material%'), { baseMaterial: null, source: 'dynamic', maxDamage: 0 })
  assert.equal(isKeyApplicable('glow', resolveItemBase('%player_material%')), true)
  assert.equal(isKeyApplicable('trim', resolveItemBase('%player_material%')), false)
  assert.equal(itemPropertyGroup('skull'), 'Item Meta')
  assert.equal(itemPropertyGroup('name'), 'Display & Text')
  assert.equal(itemPropertyGroup('bedrock'), 'Bedrock')
})
