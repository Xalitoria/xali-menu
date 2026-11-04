// Turns a model json into the quads the game draws: follows the parents, resolves the texture names, and cuts the faces of
// the elements to the same corners, in the same order, with the same texture coordinates as the game's FaceBakery. Nothing here
// needs a browser, so the numbers are tested.

import { split } from './assets.js'

/** A model with its parents folded in. */
export class Model {
  constructor({ textures = {}, elements = null, display = {}, guiLight = null, builtin = null } = {}) {
    this.textures = textures
    this.elements = elements
    this.display = display
    this.guiLight = guiLight
    /** `generated` (flat layers), `entity` (drawn by code), or null for a model with elements. */
    this.builtin = builtin
  }
}

/**
 * Reads [key] (`minecraft:block/stone`) and its parents. A child's textures and display transforms replace the parent's by name;
 * its elements, when it has any, replace all of the parent's.
 * @param {{json(ns: string, folder: string, path: string): Promise<any>}} assets
 * @returns {Promise<Model|null>} null if the model, or a parent, is not there
 */
export async function loadModel(assets, key, depth = 0) {
  if (depth > 16) return null
  const { ns, path } = split(key)
  if (ns === 'minecraft' && path === 'builtin/generated') return new Model({ builtin: 'generated' })
  if (ns === 'minecraft' && (path === 'builtin/entity' || path === 'builtin/missing')) return new Model({ builtin: 'entity' })
  const json = await assets.json(ns, 'models', path)
  if (!json) return null
  const parent = json.parent ? await loadModel(assets, json.parent, depth + 1) : new Model()
  if (!parent) return null
  return new Model({
    textures: { ...parent.textures, ...json.textures },
    elements: json.elements ?? parent.elements,
    display: { ...parent.display, ...json.display },
    guiLight: json.gui_light ?? parent.guiLight,
    builtin: parent.builtin,
  })
}

/** The texture a name stands for: `#side` is looked up in the model's textures, until it is a path. @returns {string|null} `ns:path` */
export function texturePath(model, reference) {
  const value = textureValue(model, reference)
  const name = typeof value === 'object' ? value?.sprite : value
  if (typeof name !== 'string' || name.startsWith('#')) return null
  const { ns, path } = split(name)
  return `${ns}:${path}`
}

/** What a texture name stands for: a path, or in newer models an object with a `sprite` (and `force_translucent`). */
function textureValue(model, reference) {
  let value = reference
  for (let i = 0; i < 12 && typeof value === 'string' && value.startsWith('#'); i++) value = model.textures[value.slice(1)]
  return value
}

const DIRECTIONS = {
  down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0],
}

/** The corners of each face, as indexes into [x1, y1, z1] and [x2, y2, z2]: FaceInfo of the game. Each is [x, y, z] with 0 for from and 1 for to. */
const CORNERS = {
  down: [[0, 0, 1], [0, 0, 0], [1, 0, 0], [1, 0, 1]],
  up: [[0, 1, 0], [0, 1, 1], [1, 1, 1], [1, 1, 0]],
  north: [[1, 1, 0], [1, 0, 0], [0, 0, 0], [0, 1, 0]],
  south: [[0, 1, 1], [0, 0, 1], [1, 0, 1], [1, 1, 1]],
  west: [[0, 1, 0], [0, 0, 0], [0, 0, 1], [0, 1, 1]],
  east: [[1, 1, 1], [1, 0, 1], [1, 0, 0], [1, 1, 0]],
}

/** The texture rectangle of a face that has no `uv`: [u1, v1, u2, v2], in sixteenths. */
export function defaultUv(from, to, face) {
  const [x1, y1, z1] = from
  const [x2, y2, z2] = to
  switch (face) {
    case 'down': return [x1, 16 - z2, x2, 16 - z1]
    case 'up': return [x1, z1, x2, z2]
    case 'north': return [16 - x2, 16 - y2, 16 - x1, 16 - y1]
    case 'south': return [x1, 16 - y2, x2, 16 - y1]
    case 'west': return [z1, 16 - y2, z2, 16 - y1]
    default: return [16 - z2, 16 - y2, 16 - z1, 16 - y1] // east
  }
}

/**
 * A face of an element as a quad.
 * @typedef {{positions: number[][], uvs: number[][], normal: number[], direction: string, texture: string|null, tintIndex: number}} Quad
 *   positions are in sixteenths of a block; uvs are 0 to 1
 */

/** The quads of every face of every element of [model]. */
export function bakeElements(model) {
  const quads = []
  for (const element of model.elements ?? []) {
    const from = element.from
    const to = element.to
    for (const [name, face] of Object.entries(element.faces ?? {})) {
      if (!(name in DIRECTIONS)) continue
      const uv = face.uv ?? defaultUv(from, to, name)
      const turn = ((face.rotation ?? 0) / 90) % 4
      const positions = CORNERS[name].map(([a, b, c]) => [a ? to[0] : from[0], b ? to[1] : from[1], c ? to[2] : from[2]])
      // The game's BlockFaceUV: corner 0 is (u1, v1), 1 is (u1, v2), 2 is (u2, v2), 3 is (u2, v1); a rotation shifts which corner a vertex takes.
      const corner = (i) => {
        const shifted = (i + turn + 4) % 4
        return [shifted === 0 || shifted === 1 ? uv[0] : uv[2], shifted === 0 || shifted === 3 ? uv[1] : uv[3]]
      }
      let normal = DIRECTIONS[name]
      let placed = positions
      if (element.rotation && element.rotation.angle) {
        const { matrix, scale, origin } = elementRotation(element.rotation)
        placed = positions.map((p) => {
          const local = p.map((v, i) => v - origin[i]).map((v, i) => v * scale[i])
          const turned = apply(matrix, local)
          return turned.map((v, i) => v + origin[i])
        })
        normal = apply(matrix, normal)
      }
      quads.push({
        positions: placed,
        uvs: [0, 1, 2, 3].map((i) => corner(i).map((v) => v / 16)),
        normal,
        direction: nearestDirection(normal),
        texture: texturePath(model, face.texture),
        translucent: Boolean(textureValue(model, face.texture)?.force_translucent),
        tintIndex: face.tintindex ?? -1,
      })
    }
  }
  return quads
}

/** The rotation of an element: a matrix, the scale that `rescale` asks for, and the origin (sixteenths). */
function elementRotation(rotation) {
  const angle = (rotation.angle * Math.PI) / 180
  const origin = rotation.origin ?? [8, 8, 8]
  const factor = rotation.rescale ? 1 / Math.cos(angle) : 1
  const scale = rotation.axis === 'x' ? [1, factor, factor] : rotation.axis === 'y' ? [factor, 1, factor] : [factor, factor, 1]
  return { matrix: axisMatrix(rotation.axis, angle), scale, origin }
}

/** The faces of a flat item: one picture for each `layerN` texture, drawn as a full-size face towards the viewer. */
export function bakeLayers(model) {
  const quads = []
  for (let layer = 0; model.textures[`layer${layer}`] !== undefined; layer++) {
    quads.push({
      positions: CORNERS.south.map(([a, b]) => [a ? 16 : 0, b ? 16 : 0, 8.5]),
      uvs: [[0, 0], [0, 1], [1, 1], [1, 0]],
      normal: [0, 0, 1],
      direction: 'south',
      texture: texturePath(model, `#layer${layer}`),
      tintIndex: layer,
    })
  }
  return quads
}

// ---- small matrix helpers (3x3, row-major, applied to column vectors)

export function axisMatrix(axis, radians) {
  const c = Math.cos(radians)
  const s = Math.sin(radians)
  if (axis === 'x') return [[1, 0, 0], [0, c, -s], [0, s, c]]
  if (axis === 'y') return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
  return [[c, -s, 0], [s, c, 0], [0, 0, 1]]
}

export function multiply(a, b) {
  return a.map((row) => [0, 1, 2].map((j) => row[0] * b[0][j] + row[1] * b[1][j] + row[2] * b[2][j]))
}

export function apply(m, v) {
  return m.map((row) => row[0] * v[0] + row[1] * v[1] + row[2] * v[2])
}

/** The axis direction a normal is closest to: what the game lights a rotated face as. */
export function nearestDirection(normal) {
  let best = 'up'
  let score = -Infinity
  for (const [name, d] of Object.entries(DIRECTIONS)) {
    const dot = d[0] * normal[0] + d[1] * normal[1] + d[2] * normal[2]
    if (dot > score + 1e-9) { best = name; score = dot }
  }
  return best
}

export { DIRECTIONS }
