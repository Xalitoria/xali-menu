import { test } from 'node:test'
import assert from 'node:assert/strict'
import { numberOf, flagOf, isDynamic, parseDynamic } from '../src/dynamic.js'

test('a number is a number, text of one too, and text with a placeholder is the fallback', () => {
  assert.equal(numberOf(3, 1), 3)
  assert.equal(numberOf('12', 1), 12)
  assert.equal(numberOf('%player_level%', 1), 1)
  assert.equal(numberOf(undefined, 7), 7)
  assert.equal(numberOf('', 7), 7)
})

test('a switch is true or false, text that says so too', () => {
  assert.equal(flagOf(true, null), true)
  assert.equal(flagOf('Off', null), false)
  assert.equal(flagOf('%player_is_op%', 'x'), 'x')
})

test('what is typed stays a number or a switch until it is something else', () => {
  assert.equal(parseDynamic('3', 'int'), 3)
  assert.equal(parseDynamic(' 42 ', 'int'), 42)
  assert.equal(parseDynamic('%level%', 'int'), '%level%')
  assert.equal(parseDynamic('1.5', 'int'), '1.5', 'it is then told as a mistake by the check')
  assert.equal(parseDynamic('true', 'boolean'), true)
  assert.equal(parseDynamic('%op%', 'boolean'), '%op%')
  assert.equal(isDynamic('%level%', 'int'), true)
  assert.equal(isDynamic(3, 'int'), false)
  assert.equal(isDynamic('no', 'boolean'), false)
})
