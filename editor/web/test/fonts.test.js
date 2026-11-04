import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { Zip } from '../src/zip.js'
import { readFonts, flatten, spaces, shift, placed, key } from '../src/fonts.js'

test('shift uses the biggest steps first and says what is left', () => {
  const advances = new Map([['a', -1], ['b', -2], ['c', -4], ['d', -8], ['e', -16], ['f', -32], ['p', 1], ['q', 8]])
  const negative = shift(-37, advances)
  assert.equal(negative.text, 'fca', '-32, -4 and -1')
  assert.equal(negative.rest, 0)
  assert.equal(shift(20, advances).text, 'qqpppp')
  assert.equal(shift(-3, new Map([['x', -2]])).rest, -1, 'what the steps cannot make is reported')
  assert.equal(shift(0, advances).text, '')
})

test('placed puts the text after the glyph back where it was', () => {
  const advances = new Map([['a', -1], ['b', -8], ['p', 1], ['q', 8]])
  const result = placed('G', -9, 20, advances)
  const sum = (text) => [...text].reduce((total, c) => total + (advances.get(c) ?? (c === 'G' ? 20 : 0)), 0)
  assert.equal(sum(result.text), 0, 'the cursor ends where it began')
  assert.equal(result.rest, 0)
  assert.equal(result.text.indexOf('G') > 0, true)
})

test('references are followed and loops are cut', () => {
  const fonts = new Map([
    ['minecraft:default', [{ type: 'bitmap', file: 'a.png' }, { type: 'reference', id: 'nexo:shift' }]],
    ['nexo:shift', [{ type: 'space', advances: { x: -1 } }, { type: 'reference', id: 'minecraft:default' }]],
  ])
  const flat = flatten(fonts, 'minecraft:default')
  assert.deepEqual(flat.map((p) => p.type), ['bitmap', 'space'])
  assert.equal(key('nexo:shift'), 'nexo:shift')
  assert.equal(key('default'), 'minecraft:default')
})

test('reads the fonts of a real pack', { skip: !process.env.XM_TEST_PACK }, async () => {
  const zip = await Zip.open(new Blob([readFileSync(process.env.XM_TEST_PACK)]))
  const fonts = await readFonts(zip)
  assert.ok(fonts.has('minecraft:default'))
  const providers = flatten(fonts, 'minecraft:default')
  assert.ok(providers.some((p) => p.type === 'bitmap'))
  assert.ok(spaces(providers).size > 0)
})
