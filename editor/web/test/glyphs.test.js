import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseHead, realign, insert } from '../src/glyphs.js'

const spaces = new Map([['a', -1], ['b', -8], ['c', -32], ['p', 1], ['q', 8], ['r', 32]])
const widthOf = (c) => (c === 'G' ? 20 : null)
const fontWidthOf = (c, font) => (c === 'G' && font === 'nexo:default' ? 20 : null)
const cursor = (text) => [...text].reduce((total, c) => total + (spaces.get(c) ?? (c === 'G' ? 20 : 0)), 0)

test('reads the push before the glyph and the pull after it', () => {
  const head = parseHead('bbbGccrrTitle', spaces, widthOf)
  assert.equal(head.glyph, 'G')
  assert.equal(head.offset, -24)
  assert.equal(head.width, 20)
  assert.equal(head.net, -24 + 20 - 64 + 64)
  assert.equal(head.rest, 'Title')
})

test('a title without a glyph at its start has no head', () => {
  assert.equal(parseHead('Title', spaces, widthOf), null)
  assert.equal(parseHead('bb', spaces, widthOf), null)
  assert.equal(parseHead('', spaces, widthOf), null)
})

test('realigning moves the glyph and keeps the text where it was', () => {
  const head = parseHead('bGcrTitle', spaces, widthOf)
  const moved = realign(head, -37, spaces)
  assert.equal(moved.rest, 0)
  assert.ok(moved.text.endsWith('Title'))
  assert.equal(cursor(moved.text), cursor('bGcrTitle'), 'the text starts at the same place')
  assert.equal(parseHead(moved.text, spaces, widthOf).offset, -37)
})

test('inserting a glyph in front of a title leaves the title where it was', () => {
  const result = insert('G', null, 20, -10, 'Menu', spaces)
  assert.equal(result.rest, 0)
  assert.equal(cursor(result.text), 0)
  assert.equal(parseHead(result.text, spaces, widthOf).offset, -10)
})

test('says what the space characters cannot make', () => {
  assert.equal(insert('G', null, 20, -10, 'x', new Map([['a', -4]])).rest !== 0, true)
})

test('a glyph of another font is wrapped in a font tag, and found again inside one', () => {
  const result = insert('G', 'nexo:default', 20, -8, 'Menu', spaces)
  assert.ok(result.text.includes('<font:nexo:default>G</font>'))
  assert.equal(cursor(result.text.replace(/<font:[^>]*>|<\/font>/g, '')), 0)
  const head = parseHead(result.text, spaces, fontWidthOf)
  assert.equal(head.font, 'nexo:default')
  assert.equal(head.offset, -8)
  assert.equal(head.rest, 'Menu')
  assert.equal(parseHead(result.text, spaces, () => null), null, 'a font without the glyph has no head')
  const moved = realign(head, 4, spaces)
  assert.equal(parseHead(moved.text, spaces, fontWidthOf).offset, 4)
})
