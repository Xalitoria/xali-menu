import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dropIndex } from '../src/reorder.js'
import { Store } from '../src/store.js'

test('a row is dropped after the rows whose middle is above the pointer', () => {
  const middles = [10, 30, 50] // the rows but the dragged one
  assert.equal(dropIndex(middles, 0), 0)
  assert.equal(dropIndex(middles, 25), 1)
  assert.equal(dropIndex(middles, 30), 1, 'on a middle it stays in front of that row')
  assert.equal(dropIndex(middles, 999), 3)
  assert.equal(dropIndex([], 5), 0)
})

test('moving by the dropped index inserts the row, it does not swap', () => {
  const store = new Store({ list: ['a', 'b', 'c', 'd'] })
  // a row dragged from 0 and dropped after the third other row lands at index 3
  store.move(['list'], 0, 3 - 0)
  assert.deepEqual(store.value.list, ['b', 'c', 'd', 'a'])
  // and from 3 to the front
  store.move(['list'], 3, 0 - 3)
  assert.deepEqual(store.value.list, ['a', 'b', 'c', 'd'])
  store.move(['list'], 1, 2 - 1)
  assert.deepEqual(store.value.list, ['a', 'c', 'b', 'd'])
})
