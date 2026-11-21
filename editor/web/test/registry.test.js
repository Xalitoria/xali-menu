import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fromLang, loadRegistries, mergeServerRegistries, registries, registryChoices, registryOf } from '../src/registry.js'

const lang = {
  'enchantment.minecraft.sharpness': 'Sharpness', 'enchantment.minecraft.fire_aspect': 'Fire Aspect',
  'effect.minecraft.speed': 'Speed',
  'attribute.name.attack_damage': 'Attack Damage', 'attribute.name.generic.max_health': 'Max Health',
  'color.minecraft.red': 'Red', 'color.minecraft.blue': 'Blue',
  'block.minecraft.banner.stripe_top.white': 'White Fess', 'block.minecraft.banner.stripe_top.red': 'Red Fess', 'block.minecraft.banner.creeper.white': 'White Creeper Charge',
  'item.minecraft.potion.effect.strong_healing': 'Potion of Healing', 'item.minecraft.splash_potion.effect.strong_healing': 'Splash Potion of Healing',
  'trim_pattern.minecraft.coast': 'Coast Armor Trim', 'trim_material.minecraft.gold': 'Gold Material',
}

test('the registries are read from the names in the language file, sorted by name', () => {
  const lists = fromLang(lang)
  assert.deepEqual(lists.enchantment, [['fire_aspect', 'Fire Aspect'], ['sharpness', 'Sharpness']])
  assert.deepEqual(lists.effect, [['speed', 'Speed']])
  assert.deepEqual(lists.attribute, [['attack_damage', 'Attack Damage'], ['max_health', 'Max Health']], 'the older name with `generic` is read too')
  assert.deepEqual(lists.color.map(([id]) => id), ['red', 'blue'])
  assert.deepEqual(lists['banner-pattern'].map(([id]) => id), ['creeper', 'stripe_top'], 'a pattern is listed once, not for each color')
  assert.deepEqual(lists['potion-type'], [['strong_healing', 'Potion of Healing']])
  assert.deepEqual(lists['trim-pattern'], [['coast', 'Coast Armor Trim']])
})

test('a key of the schema is given its registry, which is the game\'s names when the language file has them and a text box when it has none', () => {
  loadRegistries(lang)
  assert.equal(registryOf({ owner: 'Item', key: 'potion-type' }), 'potion-type')
  assert.equal(registryOf({ owner: 'Item', key: 'enchants' }), 'enchantment')
  assert.equal(registryOf({ owner: 'PotionEffect', key: 'type' }), 'effect')
  assert.equal(registryOf({ owner: 'Action:message', key: 'type' }), null)
  assert.deepEqual(registryChoices({ owner: 'PotionEffect', key: 'type' }), [['speed', 'Speed']])
  assert.equal(registryChoices({ owner: 'Item', key: 'nbt' }), null)
  loadRegistries({})
  assert.ok(registries.get('attribute-slot').some(([v]) => v === 'mainhand'))
  assert.ok(registries.get('fish-pattern').some(([v]) => v === 'KOB'))
  assert.equal(registryChoices({ owner: 'PotionEffect', key: 'type' }), null)
  assert.equal(registries.get('color').length, 16, 'the dye colors are known without the file')
  assert.equal(registryOf({ owner: 'Action:sound', key: 'sound' }), 'sound')
  assert.equal(registryOf({ owner: 'Action:particle', key: 'particle' }), 'particle')
  assert.equal(registryOf({ owner: 'Catalog:entities', key: 'types' }), 'entity-type')
  assert.ok(registryChoices({ owner: 'Action:sound', key: 'sound' }).some(([v]) => v === 'entity.player.levelup'))
  assert.ok(registryChoices({ owner: 'Action:particle', key: 'particle' }).some(([v]) => v === 'heart'))
  mergeServerRegistries({ sound: ['custom.ding', 'entity.player.levelup'], particle: ['flame'] })
  assert.ok(registries.get('sound').some(([v]) => v === 'custom.ding'))
  assert.deepEqual(registries.get('particle'), [['flame', 'Flame']])
})
