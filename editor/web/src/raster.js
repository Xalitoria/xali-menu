// Draws an item the way the game's inventory does, in software so that every browser gives the same pixels: the model is placed
// by its `gui` display transform, projected straight onto a 16 by 16 cell, lit with the game's two item lights, and sampled with
// nearest-neighbour texture lookups at pixel centres. [scale] is how many screen pixels one pixel of the game is.

import { DIRECTIONS, apply, axisMatrix, multiply } from './bake.js'

/**
 * The two lights of an item with `gui_light: side`. The game builds them from the angles in Lighting.java and the face normals
 * it lights are turned the same way as the model; these are the resulting directions, which reproduce the brightness of the
 * top, left and right faces of the block icons of the game (1.0, 0.64 and 0.40).
 */
const LIGHT_0 = [0.3933224, 0.9039666, -0.1677554]
const LIGHT_1 = [-0.6832039, 0.6843611, 0.2547202]

/** A fully drawn face is never darker than this and the lights add at most to 1. */
const AMBIENT = 0.4
const DIFFUSE = 0.6

/** The brightness of a face with the normal [n] (after the model is turned), for `gui_light: side`. */
export function sideLight(n) {
  const a = Math.max(0, n[0] * LIGHT_0[0] + n[1] * LIGHT_0[1] + n[2] * LIGHT_0[2])
  const b = Math.max(0, n[0] * LIGHT_1[0] + n[1] * LIGHT_1[1] + n[2] * LIGHT_1[2])
  return Math.min(1, (a + b) * DIFFUSE + AMBIENT)
}

/** The matrix of the model's display transform: the rotation as the game builds it (x, then y, then z about the model), scale and translation in blocks. */
export function transformOf(display) {
  const rotation = (display?.rotation ?? [0, 0, 0]).map((d) => (d * Math.PI) / 180)
  // Quaternionf.rotationXYZ: the vector is turned about z first, then y, then x.
  const matrix = multiply(multiply(axisMatrix('x', rotation[0]), axisMatrix('y', rotation[1])), axisMatrix('z', rotation[2]))
  const clamp = (v, limit) => Math.max(-limit, Math.min(limit, v))
  return {
    matrix,
    scale: (display?.scale ?? [1, 1, 1]).map((v) => clamp(v, 4)),
    translation: (display?.translation ?? [0, 0, 0]).map((v) => clamp(v, 80) / 16),
  }
}

/**
 * @typedef {{quads: import('./bake.js').Quad[], display?: object, guiLight?: string|null, tints?: (number|null|undefined)[], alpha?: 'cutout'|'translucent'}} Layer
 * @typedef {{width: number, height: number, data: Uint8ClampedArray}} Image
 */

/**
 * Draws [layers] in order, sharing one depth buffer, into an image of 16 * [scale] pixels square.
 * @param {Layer[]} layers
 * @param {(key: string) => import('./assets.js').Texture|null} texture the pixels of a texture by `ns:path`
 * @returns {Image}
 */
export function renderItem(layers, texture, scale = 3) {
  const size = 16 * scale
  const out = new Uint8ClampedArray(size * size * 4)
  const depth = new Float32Array(size * size).fill(-Infinity)
  for (const layer of layers) {
    const { matrix, scale: s, translation } = transformOf(layer.display)
    const flat = layer.guiLight === 'front'
    // Flat items are drawn translucent (a pixel under a tenth is dropped, the rest is blended). Blocks are drawn as the block is: cutout
    // (a half transparent pixel is either dropped or whole), or translucent for glass and the like.
    const mode = layer.alpha ?? (flat ? 'translucent' : 'cutout')
    for (const quad of layer.quads) {
      const normal = apply(matrix, quad.normal)
      if (normal[2] <= 1e-6) continue // facing away from the viewer
      const lit = flat ? 1 : sideLight(apply(matrix, DIRECTIONS[quad.direction]))
      const tex = quad.texture ? texture(quad.texture) : null
      const tint = layer.tints?.[quad.tintIndex]
      const vertices = quad.positions.map((p, i) => {
        const centred = p.map((v, axis) => (v / 16 - 0.5) * s[axis])
        const turned = apply(matrix, centred).map((v, axis) => v + translation[axis])
        return { x: (8 + 16 * turned[0]) * scale, y: (8 - 16 * turned[1]) * scale, z: 16 * turned[2], u: quad.uvs[i][0], v: quad.uvs[i][1] }
      })
      for (const triangle of [[0, 1, 2], [0, 2, 3]]) fill(out, depth, size, triangle.map((i) => vertices[i]), tex, tint, lit, quad.translucent ? 'translucent' : mode)
    }
  }
  return { width: size, height: size, data: out }
}

/** One triangle: the pixels whose centre is inside it (a pixel on an edge belongs to the triangle on its top or left side). */
function fill(out, depth, size, [a, b, c], tex, tint, lit, mode) {
  const area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
  if (Math.abs(area) < 1e-9) return
  const minX = Math.max(0, Math.floor(Math.min(a.x, b.x, c.x)))
  const maxX = Math.min(size - 1, Math.ceil(Math.max(a.x, b.x, c.x)))
  const minY = Math.max(0, Math.floor(Math.min(a.y, b.y, c.y)))
  const maxY = Math.min(size - 1, Math.ceil(Math.max(a.y, b.y, c.y)))
  // The edges are walked in the winding order of the triangle, so that "inside" is the same side for both windings.
  const sign = area > 0 ? 1 : -1
  const edges = [[a, b], [b, c], [c, a]].map(([p, q]) => {
    const dx = (q.x - p.x) * sign
    const dy = (q.y - p.y) * sign
    return { p, dx, dy, topLeft: dy < 0 || (dy === 0 && dx > 0) }
  })
  const red = tint === null || tint === undefined ? 1 : ((tint >> 16) & 255) / 255
  const green = tint === null || tint === undefined ? 1 : ((tint >> 8) & 255) / 255
  const blue = tint === null || tint === undefined ? 1 : (tint & 255) / 255
  for (let y = minY; y <= maxY; y++) {
    for (let x = minX; x <= maxX; x++) {
      const px = x + 0.5
      const py = y + 0.5
      let inside = true
      for (const e of edges) {
        const w = e.dx * (py - e.p.y) - e.dy * (px - e.p.x)
        if (w < 0 || (w === 0 && !e.topLeft)) { inside = false; break }
      }
      if (!inside) continue
      const w0 = ((b.x - px) * (c.y - py) - (b.y - py) * (c.x - px)) / area
      const w1 = ((c.x - px) * (a.y - py) - (c.y - py) * (a.x - px)) / area
      const w2 = 1 - w0 - w1
      const z = w0 * a.z + w1 * b.z + w2 * c.z
      const at = y * size + x
      if (z < depth[at]) continue
      let r = 255, g = 0, bl = 255, alpha = 255
      if (tex) {
        const u = w0 * a.u + w1 * b.u + w2 * c.u
        const v = w0 * a.v + w1 * b.v + w2 * c.v
        const tx = Math.min(tex.width - 1, Math.max(0, Math.floor(u * tex.width)))
        const ty = Math.min(tex.height - 1, Math.max(0, Math.floor(v * tex.height)))
        const i = (ty * tex.width + tx) * 4
        r = tex.data[i]; g = tex.data[i + 1]; bl = tex.data[i + 2]; alpha = tex.data[i + 3]
      } else {
        // The game's missing texture: purple and black squares, 8 pixels each.
        const u = w0 * a.u + w1 * b.u + w2 * c.u
        const v = w0 * a.v + w1 * b.v + w2 * c.v
        const dark = (Math.floor(u * 2) + Math.floor(v * 2)) % 2 === 0
        r = dark ? 0 : 248; g = 0; bl = dark ? 0 : 248
      }
      if (mode === 'cutout') {
        if (alpha < 127.5) continue
        alpha = 255
      } else if (alpha < 25.5) continue // below 0.1 the game discards the pixel
      depth[at] = z
      const o = at * 4
      const f = lit
      const sr = r * red * f, sg = g * green * f, sb = bl * blue * f
      if (alpha >= 255) { out[o] = sr; out[o + 1] = sg; out[o + 2] = sb; out[o + 3] = 255 }
      else {
        const a1 = alpha / 255
        const a0 = out[o + 3] / 255
        const outA = a1 + a0 * (1 - a1)
        out[o] = (sr * a1 + out[o] * a0 * (1 - a1)) / outA
        out[o + 1] = (sg * a1 + out[o + 1] * a0 * (1 - a1)) / outA
        out[o + 2] = (sb * a1 + out[o + 2] * a0 * (1 - a1)) / outA
        out[o + 3] = outA * 255
      }
    }
  }
}

/**
 * Adds the sheen of an enchanted item to [image], where the item is drawn: the glint texture, turned 10 degrees and repeated eight
 * times over the item, is added to the item as the game blends it, each channel squared after the 0.75 of the glint option. The sheen
 * moves in the game; this is one moment of it.
 * @param {Image} image
 * @param {{width: number, height: number, data: Uint8ClampedArray}} glint the glint texture
 */
export function addGlint(image, glint) {
  const { width, height, data } = image
  const angle = (10 * Math.PI) / 180
  const cos = Math.cos(angle)
  const sin = Math.sin(angle)
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const at = (y * width + x) * 4
      if (data[at + 3] === 0) continue
      const u = ((x + 0.5) / width) * 8
      const v = ((y + 0.5) / height) * 8
      const gu = u * cos - v * sin
      const gv = u * sin + v * cos
      const tx = ((Math.floor((gu - Math.floor(gu)) * glint.width) % glint.width) + glint.width) % glint.width
      const ty = ((Math.floor((gv - Math.floor(gv)) * glint.height) % glint.height) + glint.height) % glint.height
      const g = (ty * glint.width + tx) * 4
      for (let c = 0; c < 3; c++) {
        const s = (glint.data[g + c] / 255) * 0.75
        data[at + c] = Math.min(255, data[at + c] + s * s * 255)
      }
    }
  }
  return image
}
