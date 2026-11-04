import { test } from 'node:test'
import assert from 'node:assert/strict'
import { barColor, barWidth } from '../src/decorations.js'
import { shadowOf } from '../src/text.js'

test('the bar of a worn item shrinks with the wear and is empty when it is gone', () => {
  assert.equal(barWidth(0, 100), 13)
  assert.equal(barWidth(50, 100), 7)
  assert.equal(barWidth(100, 100), 0)
  assert.equal(barWidth(1, 1561), 13)
})

test('the bar goes from green through yellow to red', () => {
  assert.equal(barColor(1), 'rgb(0, 255, 0)')
  assert.equal(barColor(0.5), 'rgb(255, 255, 0)')
  assert.equal(barColor(0), 'rgb(255, 0, 0)')
})

test('a shadow is a quarter of each channel', () => {
  assert.equal(shadowOf('#ffffff'), '#3f3f3f')
  assert.equal(shadowOf('#fc54fc'), '#3f153f')
})
