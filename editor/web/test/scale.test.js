import { test } from 'node:test'
import assert from 'node:assert/strict'
import { deviceScale, fitScale, fitBox, viewOf } from '../src/scale.js'

test('a pixel of the game is a whole number of screen pixels at any zoom', () => {
  for (const ratio of [0.5, 0.75, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3]) {
    for (const gui of [1, 2, 3, 4, 5, 6]) {
      const device = deviceScale(gui, ratio)
      assert.ok(Number.isInteger(device) && device >= 1, `${gui} at ${ratio}`)
      // and the size on the page is exactly that many screen pixels
      assert.ok(Math.abs(viewOf(gui, ratio).css(16) * ratio - 16 * device) < 1e-9)
    }
  }
})

test('the scale follows the zoom: more zoom, more screen pixels per pixel of the game', () => {
  assert.equal(deviceScale(3, 1), 3)
  assert.equal(deviceScale(3, 1.25), 4)
  assert.equal(deviceScale(3, 2), 6)
  assert.equal(deviceScale(2, 0.5), 1)
  assert.equal(deviceScale(1, 0.25), 1, 'never below one')
})

test('fit takes the biggest scale whose window fits, and 1 when none does', () => {
  assert.equal(fitScale(176, 1000, 1), 5)
  assert.equal(fitScale(176, 540, 1), 3)
  assert.equal(fitScale(176, 100, 1), 1)
  assert.equal(fitScale(176, 1000, 2), 5, 'at a ratio of 2 the page pixels are twice as many per screen pixel, so the same scale fits')
})

test('fit to a box looks at the height as well as the width', () => {
  assert.equal(fitBox(192, 238, 1000, 1000, 1), 4)
  assert.equal(fitBox(192, 238, 1000, 480, 1), 2, 'a tall window in a short space')
  assert.equal(fitBox(192, 238, 300, 1000, 1), 1)
})
