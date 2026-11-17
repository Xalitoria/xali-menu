import { test } from 'node:test'
import assert from 'node:assert/strict'
import { hexOf, label, DYES, SHAPES, editorFor } from '../src/editors.js'
import { fireworkColor } from '../src/itemrender.js'

test('a color is read as #rrggbb in lower case, or the fallback', () => {
  assert.equal(hexOf('#FF8000'), '#ff8000')
  assert.equal(hexOf('red'), '#ff0000')
  assert.equal(hexOf(undefined, '#123456'), '#123456')
})

test('the dyes are the sixteen of the game, and their names are written as the menu file does', () => {
  assert.equal(DYES.length, 16)
  assert.ok(DYES.every(([name, hex]) => /^[a-z_]+$/.test(name) && /^#[0-9a-f]{6}$/.test(hex)))
  assert.equal(label('light_gray'), 'Light gray')
  assert.deepEqual(SHAPES.map(([v]) => v), ['BALL', 'BALL_LARGE', 'STAR', 'BURST', 'CREEPER'], 'the values of the FireworkType enum')
})

test('banners, fireworks and colors have a control of their own, other fields do not', () => {
  assert.ok(editorFor({ owner: 'Item', key: 'banner' }).block)
  assert.ok(editorFor({ owner: 'Item', key: 'firework' }).block)
  assert.ok(editorFor({ owner: 'Item', key: 'color' }))
  assert.equal(editorFor({ owner: 'Item', key: 'nbt' }), null)
  assert.equal(editorFor({ key: 'banner' }), null)
})

test('a firework star shows the average of the colors of its first burst', () => {
  assert.equal(fireworkColor({}), null)
  assert.equal(fireworkColor({ firework: { effects: [{ colors: ['#ff0000', '#0000ff'] }, { colors: ['#00ff00'] }] } }), 0x7f007f)
})
