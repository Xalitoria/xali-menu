import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Model, loadModel, bakeElements, bakeLayers, defaultUv, texturePath, nearestDirection } from '../src/bake.js'
import { renderItem, sideLight } from '../src/raster.js'

const files = {
  'models:block/cube_all': { parent: 'block/cube', textures: { particle: '#all', down: '#all', up: '#all', north: '#all', east: '#all', south: '#all', west: '#all' } },
  'models:block/cube': {
    parent: 'block/block',
    elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: Object.fromEntries(['down', 'up', 'north', 'south', 'west', 'east'].map((d) => [d, { texture: `#${d}`, cullface: d }])) }],
  },
  'models:block/block': { gui_light: 'side', display: { gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] } } },
  'models:block/stone': { parent: 'minecraft:block/cube_all', textures: { all: 'minecraft:block/stone' } },
  'models:item/generated': { gui_light: 'front', display: { gui: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] } } },
}
const assets = { json: async (ns, folder, path) => files[`${folder}:${path.replace(/^minecraft:/, '')}`] ?? null }

function solid(width, height, [r, g, b, a = 255]) {
  const data = new Uint8ClampedArray(width * height * 4)
  for (let i = 0; i < width * height; i++) data.set([r, g, b, a], i * 4)
  return { width, height, data }
}

const pixel = (image, x, y) => [...image.data.slice((y * image.width + x) * 4, (y * image.width + x) * 4 + 4)]

test('a model takes its parents textures, elements and display', async () => {
  const model = await loadModel(assets, 'minecraft:block/stone')
  assert.equal(model.elements.length, 1)
  assert.equal(model.guiLight, 'side')
  assert.deepEqual(model.display.gui.rotation, [30, 225, 0])
  assert.equal(texturePath(model, '#up'), 'minecraft:block/stone')
  assert.equal(await loadModel(assets, 'minecraft:block/nope'), null)
  assert.equal((await loadModel(assets, 'builtin/generated')).builtin, 'generated')
})

test('faces without uv get the corners of the game, and a face rotation turns them', () => {
  assert.deepEqual(defaultUv([0, 0, 0], [16, 16, 16], 'up'), [0, 0, 16, 16])
  assert.deepEqual(defaultUv([2, 4, 6], [10, 12, 14], 'north'), [6, 4, 14, 12])
  assert.deepEqual(defaultUv([2, 4, 6], [10, 12, 14], 'east'), [2, 4, 10, 12])
  const model = new Model({
    textures: { t: 'a:b' },
    elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: { south: { texture: '#t', uv: [0, 0, 8, 16] }, up: { texture: '#t', uv: [0, 0, 16, 16], rotation: 90 } } }],
  })
  const [south, up] = bakeElements(model)
  assert.deepEqual(south.uvs, [[0, 0], [0, 1], [0.5, 1], [0.5, 0]])
  assert.deepEqual(up.uvs, [[0, 1], [1, 1], [1, 0], [0, 0]], 'turned a quarter')
  assert.equal(south.direction, 'south')
})

test('a rotated element is lit as the face it is nearest to', () => {
  const model = new Model({
    textures: { t: 'a:b' },
    elements: [{ from: [0, 0, 8], to: [16, 16, 8], rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true }, faces: { north: { texture: '#t' } } }],
  })
  const [quad] = bakeElements(model)
  assert.equal(nearestDirection(quad.normal), quad.direction)
  const xs = quad.positions.map((p) => p[0])
  assert.ok(Math.max(...xs) - Math.min(...xs) > 15, 'rescale keeps the width')
})

test('a block icon has a bright top, a lighter left (east) side and a darker right (north) side, like the game', async () => {
  const model = await loadModel(assets, 'minecraft:block/stone')
  const white = solid(16, 16, [255, 255, 255])
  const image = renderItem([{ quads: bakeElements(model), display: model.display.gui, guiLight: model.guiLight }], () => white, 2)
  assert.equal(image.width, 32)
  const top = pixel(image, 16, 8)[0]
  const left = pixel(image, 9, 21)[0]
  const right = pixel(image, 23, 21)[0]
  assert.equal(top, 255)
  assert.ok(Math.abs(left / 255 - 0.64) < 0.03, `left ${left}`)
  assert.ok(Math.abs(right / 255 - 0.4) < 0.03, `right ${right}`)
  assert.equal(pixel(image, 0, 0)[3], 0, 'the corner is empty')
  assert.equal(pixel(image, 16, 16)[3], 255)
})

test('the three faces of a block cover the cell the way the icons of the game do', async () => {
  const model = await loadModel(assets, 'minecraft:block/stone')
  const image = renderItem([{ quads: bakeElements(model), display: model.display.gui, guiLight: model.guiLight }], () => solid(16, 16, [255, 255, 255]), 2)
  // The icon of stone that the wiki renders with the game (32 pixels) spans columns 2 to 29 and rows 1 to 30.
  const columns = []
  const rows = []
  for (let y = 0; y < 32; y++) for (let x = 0; x < 32; x++) if (pixel(image, x, y)[3] > 0) { columns.push(x); rows.push(y) }
  assert.deepEqual([Math.min(...columns), Math.max(...columns)], [2, 29])
  assert.deepEqual([Math.min(...rows), Math.max(...rows)], [1, 30])
})

test('a flat item fills its cell with its texture, upright, and a transparent pixel stays empty', async () => {
  const model = new Model({ builtin: 'generated', textures: { layer0: 'a:b' }, display: files['models:item/generated'].display, guiLight: 'front' })
  const data = new Uint8ClampedArray(16 * 16 * 4)
  for (let i = 0; i < 256; i++) data.set(i < 16 ? [255, 0, 0, 255] : i === 255 ? [0, 0, 0, 0] : [0, 0, 255, 255], i * 4)
  const texture = { width: 16, height: 16, data }
  const image = renderItem([{ quads: bakeLayers(model), display: model.display.gui, guiLight: 'front' }], () => texture, 3)
  assert.deepEqual(pixel(image, 0, 0), [255, 0, 0, 255], 'the top row is the top')
  assert.deepEqual(pixel(image, 20, 20), [0, 0, 255, 255])
  assert.equal(pixel(image, 47, 47)[3], 0)
})

test('a tint multiplies the color of its layer', () => {
  const quads = bakeLayers(new Model({ textures: { layer0: 'a:b' } }))
  const image = renderItem([{ quads, guiLight: 'front', tints: [0x804020] }], () => solid(16, 16, [255, 255, 255]), 1)
  assert.deepEqual(pixel(image, 8, 8), [128, 64, 32, 255])
})

test('side light is the game numbers for a face turned by 30 and 225 degrees', () => {
  // up 1.0; north 0.4; east 0.64
  const c30 = Math.cos(Math.PI / 6), s30 = Math.sin(Math.PI / 6)
  const turn = ([x, y, z]) => {
    const a = (225 * Math.PI) / 180
    const [x1, z1] = [x * Math.cos(a) + z * Math.sin(a), -x * Math.sin(a) + z * Math.cos(a)]
    return [x1, y * c30 - z1 * s30, y * s30 + z1 * c30]
  }
  assert.equal(sideLight(turn([0, 1, 0])), 1)
  assert.ok(Math.abs(sideLight(turn([0, 0, -1])) - 0.4) < 0.01)
  assert.ok(Math.abs(sideLight(turn([1, 0, 0])) - 0.64) < 0.01)
})

import { addGlint } from '../src/raster.js'

test('the glint brightens the pixels of the item and leaves empty ones alone', () => {
  const data = new Uint8ClampedArray(4 * 4 * 4)
  data.set([10, 10, 10, 255], 0)
  const glint = { width: 2, height: 2, data: new Uint8ClampedArray([255, 0, 255, 255, 255, 0, 255, 255, 255, 0, 255, 255, 255, 0, 255, 255]) }
  addGlint({ width: 4, height: 4, data }, glint)
  assert.ok(data[0] > 10 && data[1] === 10 && data[2] > 10, 'purple is added')
  assert.equal(data[7], 0)
  assert.equal(data[4], 0, 'an empty pixel stays empty')
})

import { downsample } from '../src/raster.js'

test('shrinking averages each square by alpha, so an edge is partly covered and the colors stay', () => {
  const data = new Uint8ClampedArray(2 * 2 * 4)
  data.set([200, 100, 50, 255], 0) // one opaque pixel of four
  const small = downsample({ width: 2, height: 2, data }, 2)
  assert.deepEqual([...small.data], [200, 100, 50, 64], 'a quarter covered, with the color of what is there')
  assert.equal(downsample({ width: 2, height: 2, data }, 1).data, data)
})
