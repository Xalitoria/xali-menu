import { test } from 'node:test'
import assert from 'node:assert/strict'
import { hexOf, label, DYES, SHAPES, editorFor } from '../src/editors.js'
import { fireworkColor } from '../src/itemrender.js'
import { roman, levelOf, amplifierOf, secondsOf, ticksOf, clock, effectOf, OPERATIONS } from '../src/effects.js'
import { shanks, TOOL_TAGS } from '../src/itemeditors.js'

test('colors, dyes and shapes are the game\'s, written as the menu file writes them', () => {
  assert.deepEqual([hexOf('#FF8000'), hexOf('red'), hexOf(undefined, '#123456')], ['#ff8000', '#ff0000', '#123456'])
  assert.equal(DYES.length, 16)
  assert.ok(DYES.every(([name, hex]) => /^[a-z_]+$/.test(name) && /^#[0-9a-f]{6}$/.test(hex)))
  assert.equal(label('light_gray'), 'Light gray')
  assert.deepEqual(SHAPES.map(([v]) => v), ['BALL', 'BALL_LARGE', 'STAR', 'BURST', 'CREEPER'], 'the values of the FireworkType enum')
  assert.equal(fireworkColor({}), null)
  assert.equal(fireworkColor({ firework: { effects: [{ colors: ['#ff0000', '#0000ff'] }, { colors: ['#00ff00'] }] } }), 0x7f007f, 'a star shows the first burst')
})

test('the fields that have a control of their own are the ones the page makes, and others are not', () => {
  const own = [['Item', 'banner'], ['Item', 'firework'], ['Item', 'color'], ['Item', 'potion'], ['Item', 'attributes'], ['Item', 'food'], ['Item', 'tool'], ['Item', 'fish'], ['Book', 'pages'], ['Action:book', 'pages']]
  for (const [owner, key] of own) assert.ok(editorFor({ owner, key }), `${owner}.${key}`)
  assert.ok(editorFor({ owner: 'Item', key: 'banner' }).block)
  for (const field of [{ owner: 'Item', key: 'nbt' }, { owner: 'Book', key: 'title' }, { key: 'banner' }]) assert.equal(editorFor(field), null)
})

test('levels, seconds, drumsticks and attribute amounts are said as people say them', () => {
  assert.deepEqual([1, 2, 4, 9, 10, 11].map(roman), ['I', 'II', 'IV', 'IX', 'X', '11'])
  assert.deepEqual([levelOf(0), levelOf(undefined), amplifierOf(3), amplifierOf(0), amplifierOf('2')], [1, 1, 2, 0, 1])
  assert.deepEqual([secondsOf(600), secondsOf(10), ticksOf(30), ticksOf(0.5), ticksOf(-3), ticksOf(secondsOf(1234))], [30, 0.5, 600, 10, 0, 1234])
  assert.deepEqual([600, 1800, 72000, -1, 0].map(clock), ['0:30', '1:30', '60:00', '∞', '0:00'])
  assert.deepEqual([effectOf('ADD', 5), effectOf('ADD', -2), effectOf('MULTIPLY_BASE', 0.5), effectOf('MULTIPLY_TOTAL', 0.5), effectOf('ADD', 'x')], ['+5', '−2', '+50% of the base', '×1.5', ''])
  assert.deepEqual(OPERATIONS.map(([v]) => v), ['ADD', 'MULTIPLY_BASE', 'MULTIPLY_TOTAL'], 'the values of the AttributeModifier enum')
  assert.deepEqual(shanks(5), { sticks: ['full', 'full', 'half', 'empty', 'empty', 'empty', 'empty', 'empty', 'empty', 'empty'], over: 0 })
  assert.equal(shanks(24).over, 4)
  assert.ok(TOOL_TAGS.every(([, tag]) => tag.startsWith('#minecraft:')))
})
