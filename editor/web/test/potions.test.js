import { test } from 'node:test'
import assert from 'node:assert/strict'
import { potionColor, WATER, EFFECT_COLORS } from '../src/potions.js'

test('a potion with no effects has the color of water', () => {
  assert.equal(potionColor(null), WATER)
  assert.equal(potionColor('awkward'), WATER)
  assert.equal(potionColor('minecraft:water'), WATER)
})

test('a base potion has the color of its effect, whatever its strength or length', () => {
  assert.equal(potionColor('healing'), EFFECT_COLORS.instant_health)
  assert.equal(potionColor('strong_healing'), EFFECT_COLORS.instant_health)
  assert.equal(potionColor('long_swiftness'), EFFECT_COLORS.speed)
})

test('the colors of several effects are averaged by their level', () => {
  const [a, b] = [EFFECT_COLORS.slowness, EFFECT_COLORS.resistance]
  const channel = (c, shift) => (c >> shift) & 255
  const expected = [16, 8, 0].map((shift) => Math.trunc((channel(a, shift) * 4 + channel(b, shift) * 3) / 7))
  assert.equal(potionColor('turtle_master'), (expected[0] << 16) | (expected[1] << 8) | expected[2])
  assert.equal(potionColor(null, [{ type: 'speed' }, { type: 'minecraft:speed', amplifier: 1 }]), EFFECT_COLORS.speed)
})

test('a type that is not the game\'s has no known color, but a typo in a custom effect is ignored', () => {
  assert.equal(potionColor('strong_nothing'), null)
  assert.equal(potionColor(null, [{ type: 'nothing' }]), WATER)
})

test('the color of a base potion changes with the type, which is what the tipped arrow shows', () => {
  assert.notEqual(potionColor('swiftness'), potionColor('healing'))
})
