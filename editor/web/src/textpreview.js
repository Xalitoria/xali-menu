// Renders formatted Minecraft text (colors, gradients, MiniMessage, Nexo/Oraxen/ItemsAdder glyphs & shifts)
// onto crisp HiDPI <canvas> elements using `Text` (Minecraft's real bitmap fonts + loaded resource pack).

import { render as renderDom, plain } from './mctext.js'

/**
 * Computes canvas dimensions and draw origins (in game pixels) from `Text.layoutLines()` output.
 * Caps vertical glyph expansion so oversized HUD background glyphs do not blow up inline input previews.
 */
export function computeCanvasMetrics(lines, {
  lineHeight = 10,
  shadow = true,
  padX = 0,
  padY = 0,
  maxAscentPad = 6,
  maxDescentPad = 6,
} = {}) {
  if (!lines || lines.length === 0) {
    return {
      width: Math.max(1, padX * 2),
      height: Math.max(8, 8 + padY * 2),
      originX: padX,
      originY: padY,
      contentWidth: 1,
    }
  }
  let minX = 0
  let maxX = 0
  for (const line of lines) {
    if (line.minX < minX) minX = line.minX
    const right = Math.max(line.width, line.maxX)
    if (right > maxX) maxX = right
  }
  const extraShadow = shadow ? 1 : 0
  const firstMinY = lines[0].minY ?? 0
  const lastMaxY = lines[lines.length - 1].maxY ?? 8
  const topPad = Math.min(maxAscentPad, Math.max(0, Math.ceil(-firstMinY)))
  const bottomPad = Math.min(maxDescentPad, Math.max(extraShadow, Math.ceil(lastMaxY - 8) + extraShadow))
  const contentWidth = Math.max(1, Math.ceil(maxX - minX) + extraShadow)
  const contentHeight = Math.max(8, (lines.length - 1) * lineHeight + 8 + topPad + bottomPad)
  return {
    width: contentWidth + padX * 2,
    height: contentHeight + padY * 2,
    originX: padX - minX,
    originY: padY + topPad,
    contentWidth,
  }
}

/**
 * Renders [text] into [container] using Minecraft's bitmap font (`textService`).
 * Zero-flicker: keeps the previous canvas visible while drawing and cancels stale async renders on fast typing.
 * Falls back to DOM `mctext.render()` if canvas/textService is unavailable.
 *
 * @param {HTMLElement} container
 * @param {import('./text.js').Text|null} textService
 * @param {string} text
 * @param {{
 *   scale?: number,
 *   color?: string,
 *   shadow?: boolean,
 *   lineHeight?: number,
 *   maxWidth?: number,
 *   align?: 'left'|'center'|'right',
 *   padX?: number,
 *   padY?: number,
 *   maxAscentPad?: number,
 *   maxDescentPad?: number,
 * }} [options]
 */
export async function renderMcTextCanvas(container, textService, text, options = {}) {
  if (!container) return null
  const token = (container._mcCanvasToken ?? 0) + 1
  container._mcCanvasToken = token

  const value = text == null ? '' : String(text)
  if (!value) {
    container.replaceChildren()
    return null
  }

  if (!textService || typeof document === 'undefined') {
    if (typeof document !== 'undefined') container.replaceChildren(renderDom(value))
    return null
  }

  const {
    scale = 2,
    color = '#ffffff',
    shadow = true,
    lineHeight = 10,
    maxWidth = Infinity,
    align = 'left',
    padX = 0,
    padY = 0,
    maxAscentPad = 6,
    maxDescentPad = 6,
  } = options

  try {
    const { lines } = await textService.layoutLines(value, { maxWidth })
    if (container._mcCanvasToken !== token) return null

    const metrics = computeCanvasMetrics(lines, { lineHeight, shadow, padX, padY, maxAscentPad, maxDescentPad })
    const ratio = (typeof window !== 'undefined' && window.devicePixelRatio) ? window.devicePixelRatio : 1
    const deviceScale = Math.max(1, Math.round(scale * ratio))

    let canvas = container.childNodes.length === 1 && container.firstChild?.tagName === 'CANVAS'
      ? container.firstChild
      : document.createElement('canvas')
    canvas.className = 'mc-canvas'
    canvas.width = Math.max(1, Math.ceil(metrics.width * deviceScale))
    canvas.height = Math.max(1, Math.ceil(metrics.height * deviceScale))
    canvas.style.width = `${canvas.width / ratio}px`
    canvas.style.height = `${canvas.height / ratio}px`
    canvas.setAttribute('role', 'img')
    canvas.setAttribute('aria-label', plain(value))

    const ctx = canvas.getContext('2d')
    if (!ctx) {
      container.replaceChildren(renderDom(value))
      return null
    }
    ctx.imageSmoothingEnabled = false
    ctx.clearRect(0, 0, canvas.width, canvas.height)

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i]
      let dx = 0
      if (align === 'center') dx = Math.floor((metrics.contentWidth - line.width) / 2)
      else if (align === 'right') dx = Math.floor(metrics.contentWidth - line.width)
      textService.drawPlaced(
        ctx,
        line.placed,
        (metrics.originX + dx) * deviceScale,
        (metrics.originY + i * lineHeight) * deviceScale,
        { scale: deviceScale, color, shadow },
      )
    }

    if (canvas.parentNode !== container || container.childNodes.length !== 1) {
      container.replaceChildren(canvas)
    }
    return canvas
  } catch {
    if (container._mcCanvasToken === token) container.replaceChildren(renderDom(value))
    return null
  }
}

/**
 * Creates a lightweight wrapper element (`<span class="mc-canvas-wrap">`) and asynchronously renders
 * [text] inside it using Minecraft's bitmap font.
 */
export function mcTextCanvas(textService, text, options = {}) {
  const tag = options.block ? 'div' : 'span'
  const wrap = document.createElement(tag)
  wrap.className = options.className ? `mc-canvas-wrap ${options.className}` : 'mc-canvas-wrap'
  renderMcTextCanvas(wrap, textService, text, options)
  return wrap
}
