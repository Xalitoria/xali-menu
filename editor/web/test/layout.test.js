import { test } from 'node:test'
import assert from 'node:assert/strict'
import { grid, rowsOf, paint, counts, strays, COLUMNS } from '../src/layout.js'
import { editorFor } from '../src/editors.js'

test('a layout is a grid of nine columns, painting a cell changes one character, and the rows come back as the file writes them', () => {
  assert.equal(COLUMNS, 9)
  assert.deepEqual(grid(['#####'], 2)[0].join(''), '#####    ')
  assert.deepEqual(grid([], 1), [Array(9).fill(' ')])
  assert.deepEqual(paint(['#########', '#       #'], 3, 1, 4, 'a'), ['#########', '#   a   #'])
  assert.deepEqual(paint([], 3, 2, 0, 'x'), ['', '', 'x'], 'rows above the one painted are kept as blanks')
  assert.deepEqual(paint(['#########', 'a'], 3, 1, 0, ' '), ['#########'], 'erasing the last character takes the row away')
  assert.deepEqual(paint(['a'], 1, 0, 3, 'bc'), ['a  b'], 'a character longer than one is cut to its first')
  assert.deepEqual(rowsOf([['a', ' '], [' ', ' ']].map((r) => [...r, ...Array(7).fill(' ')])), ['a'])
  assert.deepEqual([...counts(['a b', 'a'])], [['a', 2], ['b', 1]])
  assert.deepEqual(strays(['a b', 'c'], { a: 'x', z: 'y' }), { unknown: ['b', 'c'], unused: ['z'] })
  assert.ok(editorFor({ owner: 'Menu', key: 'matrix' }).block)
  assert.equal(editorFor({ owner: 'Menu', key: 'layout' }), null)
})
