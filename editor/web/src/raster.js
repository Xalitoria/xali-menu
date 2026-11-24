// Software item rasterizer matching Minecraft's GUI display transform, two item lights, and nearest-neighbour sampling.
// Supports items whose `display.gui` scale/translation or element geometry extends beyond the 16x16 slot bounds
// (e.g. multi-slot Nexo UI buttons and enlarged player heads) by sizing the output canvas to the projected bounds
// and returning `{ width, height, offsetX, offsetY, data }`.

import { DIRECTIONS, apply, axisMatrix, multiply } from './bake.js'

const LIGHT_0 = [0.3933224, 0.9039666, -0.1677554]
const LIGHT_1 = [-0.6832039, 0.6843611, 0.2547202]
const AMBIENT = 0.4
const DIFFUSE = 0.6

/** Safety clamp in Minecraft GUI pixels beyond the 16x16 slot so malformed models cannot OOM the browser. */
const MAX_OVERFLOW_PX = 96

export function sideLight(n) {
  const a = Math.max(0, n[0] * LIGHT_0[0] + n[1] * LIGHT_0[1] + n[2] * LIGHT_0[2])
  const b = Math.max(0, n[0] * LIGHT_1[0] + n[1] * LIGHT_1[1] + n[2] * LIGHT_1[2])
  return Math.min(1, (a + b) * DIFFUSE + AMBIENT)
}

export function transformOf(display) {
  const rotation = (display?.rotation ?? [0, 0, 0]).map((d) => (d * Math.PI) / 180)
  const matrix = multiply(multiply(axisMatrix('x', rotation[0]), axisMatrix('y', rotation[1])), axisMatrix('z', rotation[2]))
  const clamp = (v, limit) => Math.max(-limit, Math.min(limit, v))
  return {
    matrix,
    scale: (display?.scale ?? [1, 1, 1]).map((v) => clamp(v, 4)),
    translation: (display?.translation ?? [0, 0, 0]).map((v) => clamp(v, 80) / 16),
  }
}

/**
 * Draws [layers] in order, sharing one depth buffer.
 * When all vertices stay inside the 16x16 slot, returns a `16*scale` square with `offsetX = 0, offsetY = 0`.
 * When `display.gui` scale/translation or model elements extend past the slot, expands the canvas cleanly
 * and reports `offsetX` / `offsetY` (in pixels at [scale]) relative to the slot's top-left corner.
 *
 * @param {Layer[]} layers
 * @param {(key: string) => import('./assets.js').Texture|null} texture
 * @param {number} [scale]
 * @param {number} [align] grid alignment for bounds (set to `oversample` when downsampling)
 * @param {boolean} [clipToSlot] clip output to the 16x16 slot (MC 1.21.6+ when `oversized_in_gui` is not true)
 */
export function renderItem(layers, texture, scale = 3, align = 1, clipToSlot = false) {
  const slotSize = 16 * scale
  const limitMin = -MAX_OVERFLOW_PX * scale
  const limitMax = (16 + MAX_OVERFLOW_PX) * scale

  // Pass 1: project every front-facing quad and determine the true bounding box.
  const prepared = []
  let minVx = 0
  let minVy = 0
  let maxVx = slotSize
  let maxVy = slotSize

  for (const layer of layers) {
    const { matrix, scale: s, translation } = transformOf(layer.display)
    const flat = layer.guiLight === 'front'
    const mode = layer.alpha ?? (flat ? 'translucent' : 'cutout')
    for (const quad of layer.quads) {
      const normal = apply(matrix, quad.normal)
      if (normal[2] <= 1e-6) continue
      const lit = layer.brightness ?? (flat ? 1 : sideLight(apply(matrix, DIRECTIONS[quad.direction])))
      const tex = quad.texture ? texture(quad.texture) : null
      const tint = layer.tints?.[quad.tintIndex]
      const vertices = quad.positions.map((p, i) => {
        const centred = p.map((v, axis) => (v / 16 - 0.5) * s[axis])
        const turned = apply(matrix, centred).map((v, axis) => v + translation[axis])
        const vx = (8 + 16 * turned[0]) * scale
        const vy = (8 - 16 * turned[1]) * scale
        if (vx < minVx) minVx = vx
        if (vx > maxVx) maxVx = vx
        if (vy < minVy) minVy = vy
        if (vy > maxVy) maxVy = vy
        return { x: vx, y: vy, z: 16 * turned[2], u: quad.uvs[i][0], v: quad.uvs[i][1] }
      })
      prepared.push({ vertices, tex, tint, lit, mode: quad.translucent ? 'translucent' : mode })
    }
  }

  const snapDown = (v) => Math.floor(v / align) * align
  const snapUp = (v) => Math.ceil(v / align) * align
  const originX = clipToSlot ? 0 : Math.max(snapDown(limitMin), Math.min(0, snapDown(minVx)))
  const originY = clipToSlot ? 0 : Math.max(snapDown(limitMin), Math.min(0, snapDown(minVy)))
  const endX = clipToSlot ? slotSize : Math.min(snapUp(limitMax), Math.max(slotSize, snapUp(maxVx)))
  const endY = clipToSlot ? slotSize : Math.min(snapUp(limitMax), Math.max(slotSize, snapUp(maxVy)))

  const width = Math.max(align, endX - originX)
  const height = Math.max(align, endY - originY)
  const out = new Uint8ClampedArray(width * height * 4)
  const depth = new Float32Array(width * height).fill(-Infinity)

  // Pass 2: rasterize shifted into [0..width, 0..height].
  for (const item of prepared) {
    const shifted = originX === 0 && originY === 0
      ? item.vertices
      : item.vertices.map((v) => ({ ...v, x: v.x - originX, y: v.y - originY }))
    for (const triangle of [[0, 1, 2], [0, 2, 3]]) {
      fill(out, depth, width, height, triangle.map((i) => shifted[i]), item.tex, item.tint, item.lit, item.mode)
    }
  }

  return { width, height, offsetX: originX, offsetY: originY, data: out }
}

function fill(out, depth, width, height, [a, b, c], tex, tint, lit, mode) {
  const area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
  if (Math.abs(area) < 1e-9) return
  const minX = Math.max(0, Math.floor(Math.min(a.x, b.x, c.x)))
  const maxX = Math.min(width - 1, Math.ceil(Math.max(a.x, b.x, c.x)))
  const minY = Math.max(0, Math.floor(Math.min(a.y, b.y, c.y)))
  const maxY = Math.min(height - 1, Math.ceil(Math.max(a.y, b.y, c.y)))
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
      const at = y * width + x
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
        const u = w0 * a.u + w1 * b.u + w2 * c.u
        const v = w0 * a.v + w1 * b.v + w2 * c.v
        const dark = (Math.floor(u * 2) + Math.floor(v * 2)) % 2 === 0
        r = dark ? 0 : 248; g = 0; bl = dark ? 0 : 248
      }
      if (mode === 'cutout') {
        if (alpha < 127.5) continue
        alpha = 255
      } else if (alpha < 25.5) continue
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

export function downsample(image, factor) {
  if (factor === 1) return image
  const width = Math.floor(image.width / factor)
  const height = Math.floor(image.height / factor)
  const out = new Uint8ClampedArray(width * height * 4)
  const area = factor * factor
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      let r = 0, g = 0, b = 0, a = 0
      for (let dy = 0; dy < factor; dy++) {
        for (let dx = 0; dx < factor; dx++) {
          const i = ((y * factor + dy) * image.width + x * factor + dx) * 4
          const alpha = image.data[i + 3]
          r += image.data[i] * alpha; g += image.data[i + 1] * alpha; b += image.data[i + 2] * alpha; a += alpha
        }
      }
      const o = (y * width + x) * 4
      if (a > 0) { out[o] = r / a; out[o + 1] = g / a; out[o + 2] = b / a }
      out[o + 3] = a / area
    }
  }
  return {
    width,
    height,
    offsetX: Math.round((image.offsetX ?? 0) / factor),
    offsetY: Math.round((image.offsetY ?? 0) / factor),
    data: out,
  }
}
