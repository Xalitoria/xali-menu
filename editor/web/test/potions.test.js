import { test } from 'node:test'
import assert from 'node:assert/strict'
import { potionColor, WATER, EFFECT_COLORS } from '../src/potions.js'

test('a potion with no effects is water, and a base potion has the color of its effect whatever its strength or length', () => {
  for (const none of [null, 'awkward', 'minecraft:water']) assert.equal(potionColor(none), WATER)
  assert.equal(potionColor('healing'), EFFECT_COLORS.instant_health)
  assert.equal(potionColor('strong_healing'), EFFECT_COLORS.instant_health)
  assert.equal(potionColor('long_swiftness'), EFFECT_COLORS.speed)
  assert.notEqual(potionColor('swiftness'), potionColor('healing'), 'a tipped arrow changes with its type')
})

test('the colors of several effects are averaged by their level, and what the game does not have is ignored', () => {
  const [a, b] = [EFFECT_COLORS.slowness, EFFECT_COLORS.resistance]
  const channel = (c, shift) => (c >> shift) & 255
  const [r, g, bl] = [16, 8, 0].map((shift) => Math.trunc((channel(a, shift) * 4 + channel(b, shift) * 3) / 7))
  assert.equal(potionColor('turtle_master'), (r << 16) | (g << 8) | bl)
  assert.equal(potionColor(null, [{ type: 'speed' }, { type: 'minecraft:speed', amplifier: 1 }]), EFFECT_COLORS.speed)
  assert.equal(potionColor('strong_nothing'), null)
  assert.equal(potionColor(null, [{ type: 'nothing' }]), WATER)
})
