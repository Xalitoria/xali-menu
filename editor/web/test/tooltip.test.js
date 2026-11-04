import { test } from 'node:test'
import assert from 'node:assert/strict'
import { tooltipLines, level } from '../src/tooltip.js'

const lang = { 'item.minecraft.diamond_sword': 'Diamond Sword', 'enchantment.minecraft.sharpness': 'Sharpness', 'enchantment.minecraft.mending': 'Mending' }

test('the name is the one of the item, or the game name of its material', () => {
  assert.deepEqual(tooltipLines({ material: 'diamond_sword' }, lang), [{ text: 'Diamond Sword', color: '#ffffff' }])
  assert.equal(tooltipLines({ material: 'diamond_sword', name: '&6Excalibur', rarity: 'EPIC' }, lang)[0].color, '#ff55ff')
  assert.equal(tooltipLines({ material: 'diamond_sword', name: '&6Excalibur' }, lang)[0].text, '&6Excalibur')
})

test('enchantments are gray with roman levels, and a single level is left out; lore is dark purple', () => {
  const lines = tooltipLines({ material: 'diamond_sword', enchants: { sharpness: 5, mending: 1 }, lore: ['&7Rare'], unbreakable: true }, lang)
  assert.deepEqual(lines.slice(1).map((l) => l.text), ['Sharpness V', 'Mending', '&7Rare', 'Unbreakable'])
  assert.deepEqual(lines.slice(1).map((l) => l.color), ['#aaaaaa', '#aaaaaa', '#aa00aa', '#5555ff'])
})

test('hiding the enchantments takes their lines away', () => {
  assert.equal(tooltipLines({ material: 'diamond_sword', enchants: { sharpness: 1 }, hide: ['enchantments'] }, lang).length, 1)
  assert.equal(level(4), 'IV')
  assert.equal(level(11), '11')
})
