import { test } from 'node:test'
import assert from 'node:assert/strict'
import { runs, plain } from '../src/mctext.js'

test('legacy codes set the color and styles, and &r clears them', () => {
  assert.deepEqual(runs('&6Gold &lBold&r plain'), [
    { text: 'Gold ', color: '#ffaa00' },
    { text: 'Bold', color: '#ffaa00', bold: true },
    { text: ' plain' },
  ])
})

test('a color code ends the styles before it, as in the game', () => {
  assert.deepEqual(runs('&lA&cB'), [{ text: 'A', bold: true }, { text: 'B', color: '#ff5555' }])
})

test('hex colors and minimessage tags work', () => {
  assert.equal(runs('&#ff8800X')[0].color, '#ff8800')
  assert.deepEqual(runs('<red>R</red>N'), [{ text: 'R', color: '#ff5555' }, { text: 'N', color: undefined }])
  assert.equal(plain('<bold>b</bold> &aok'), 'b ok')
})

test('text that is not a code stays as typed', () => {
  assert.equal(plain('a < b & c'), 'a < b & c')
})

test('a font tag puts its key on the runs inside it', () => {
  assert.deepEqual(runs('a<font:nexo:default>b</font>c'), [{ text: 'a' }, { text: 'b', font: 'nexo:default' }, { text: 'c', font: undefined }])
})
