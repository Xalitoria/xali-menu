import { test } from 'node:test'
import assert from 'node:assert/strict'
import { closest, hint, distance } from '../src/suggest.js'

test('the closest word is found by style first, then by a few edits, and not when nothing is near', () => {
  assert.equal(closest('on_open', ['on-open', 'on-close']), 'on-open')
  assert.equal(closest('onOpen', ['on-open', 'on-close']), 'on-open')
  assert.equal(closest('priorty', ['slot', 'slots', 'priority']), 'priority')
  assert.equal(closest('zebra', ['slot', 'slots']), null)
  assert.equal(closest('slot', ['slot']), null)
  assert.equal(hint('slott', ['slot']), " (did you mean 'slot'?)")
  assert.equal(hint('zebra', ['slot']), '')
  assert.equal(distance('kitten', 'sitting'), 3)
})
