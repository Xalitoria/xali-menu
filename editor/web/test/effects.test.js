import { test } from 'node:test'
import assert from 'node:assert/strict'
import { roman, levelOf, amplifierOf, secondsOf, ticksOf, clock, effectOf, OPERATIONS } from '../src/effects.js'
import { editorFor } from '../src/editors.js'

test('a level is written in numerals up to ten, as the game does, and in digits after', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 9, 10].map(roman), ['I', 'II', 'III', 'IV', 'V', 'IX', 'X'])
  assert.equal(roman(11), '11')
  assert.equal(roman(256), '256')
})

test('the amplifier of the file is the level minus one', () => {
  assert.equal(levelOf(0), 1)
  assert.equal(levelOf(undefined), 1)
  assert.equal(amplifierOf(3), 2)
  assert.equal(amplifierOf(0), 0, 'never below level I')
  assert.equal(amplifierOf('2'), 1)
})

test('ticks are shown as seconds and read back, down to a twentieth of a second', () => {
  assert.equal(secondsOf(600), 30)
  assert.equal(secondsOf(10), 0.5)
  assert.equal(ticksOf(30), 600)
  assert.equal(ticksOf(0.5), 10)
  assert.equal(ticksOf(-3), 0)
  assert.equal(ticksOf(secondsOf(1234)), 1234)
})

test('how long an effect lasts is shown as the tooltip of the game does', () => {
  assert.equal(clock(600), '0:30')
  assert.equal(clock(1800), '1:30')
  assert.equal(clock(72000), '60:00')
  assert.equal(clock(-1), '∞')
  assert.equal(clock(0), '0:00')
})

test('an attribute amount says what it does with each operation', () => {
  assert.equal(effectOf('ADD', 5), '+5')
  assert.equal(effectOf('ADD', -2), '−2')
  assert.equal(effectOf('MULTIPLY_BASE', 0.5), '+50% of the base')
  assert.equal(effectOf('MULTIPLY_TOTAL', 0.5), '×1.5')
  assert.equal(effectOf('ADD', 'x'), '')
  assert.deepEqual(OPERATIONS.map(([v]) => v), ['ADD', 'MULTIPLY_BASE', 'MULTIPLY_TOTAL'], 'the values of the AttributeModifier enum')
})

test('potion effects and attributes have a control of their own', () => {
  assert.ok(editorFor({ owner: 'Item', key: 'potion' }).block)
  assert.ok(editorFor({ owner: 'Item', key: 'attributes' }).block)
})

test('the pages of a book have a control of their own, in the item and in the action', () => {
  assert.ok(editorFor({ owner: 'Book', key: 'pages' }).block)
  assert.ok(editorFor({ owner: 'Action:book', key: 'pages' }).block)
  assert.equal(editorFor({ owner: 'Book', key: 'title' }), null)
})
