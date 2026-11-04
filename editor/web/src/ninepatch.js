// Stretches a sprite of the game the way its `nine_slice` scaling says: the corners as they are, the edges and the middle stretched.

/** The pixels of a texture as something that can be drawn: a canvas, made once. */
export function canvasOf(texture) {
  if (!texture) return null
  if (!texture.canvas) {
    texture.canvas = new OffscreenCanvas(texture.width, texture.height)
    texture.canvas.getContext('2d').putImageData(new ImageData(texture.data, texture.width, texture.height), 0, 0)
  }
  return texture.canvas
}

/**
 * Draws [texture] into the rectangle [x], [y], [width], [height] (screen pixels). [border] is the size of the corners in pixels of the texture
 * (a number, or {left, top, right, bottom}); [scale] is how much one pixel of the texture is enlarged.
 */
export function nineSlice(ctx, texture, x, y, width, height, border, scale) {
  const source = canvasOf(texture)
  if (!source) return
  const b = typeof border === 'number' ? { left: border, top: border, right: border, bottom: border } : border
  const bl = b.left * scale, bt = b.top * scale, br = b.right * scale, bb = b.bottom * scale
  const mw = texture.width - b.left - b.right
  const mh = texture.height - b.top - b.bottom
  ctx.imageSmoothingEnabled = false
  const cut = (sx, sy, sw, sh, dx, dy, dw, dh) => { if (sw > 0 && sh > 0 && dw > 0 && dh > 0) ctx.drawImage(source, sx, sy, sw, sh, dx, dy, dw, dh) }
  const xs = [x, x + bl, x + width - br]
  const ys = [y, y + bt, y + height - bb]
  const sxs = [0, b.left, texture.width - b.right]
  const sys = [0, b.top, texture.height - b.bottom]
  const sws = [b.left, mw, b.right]
  const shs = [b.top, mh, b.bottom]
  const dws = [bl, width - bl - br, br]
  const dhs = [bt, height - bt - bb, bb]
  for (let r = 0; r < 3; r++) for (let c = 0; c < 3; c++) cut(sxs[c], sys[r], sws[c], shs[r], xs[c], ys[r], dws[c], dhs[r])
}

/** A part of a texture: [sx], [sy], [sw], [sh] drawn at [x], [y] with each pixel [scale] times as large. */
export function blit(ctx, texture, x, y, sx, sy, sw, sh, scale) {
  const source = canvasOf(texture)
  if (!source) return
  ctx.imageSmoothingEnabled = false
  ctx.drawImage(source, sx, sy, sw, sh, x, y, sw * scale, sh * scale)
}
