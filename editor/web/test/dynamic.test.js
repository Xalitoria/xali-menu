import { test } from 'node:test'
import assert from 'node:assert/strict'
import { numberOf, flagOf, isDynamic, parseDynamic } from '../src/dynamic.js'

test('a number or a switch is read from text too, and text with a placeholder is the fallback until it is worked out', () => {
  assert.deepEqual([numberOf(3, 1), numberOf('12', 1), numberOf('%player_level%', 1), numberOf(undefined, 7), numberOf('', 7)], [3, 12, 1, 7, 7])
  assert.deepEqual([flagOf(true, null), flagOf('Off', null), flagOf('%player_is_op%', 'x')], [true, false, 'x'])
  assert.deepEqual([parseDynamic('3', 'int'), parseDynamic(' 42 ', 'int'), parseDynamic('%level%', 'int'), parseDynamic('1.5', 'int')], [3, 42, '%level%', '1.5'])
  assert.deepEqual([parseDynamic('true', 'boolean'), parseDynamic('%op%', 'boolean')], [true, '%op%'])
  assert.deepEqual([isDynamic('%level%', 'int'), isDynamic(3, 'int'), isDynamic('no', 'boolean')], [true, false, false])
})
