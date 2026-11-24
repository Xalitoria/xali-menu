// Draws text with the fonts of the game: layers the resource pack's font providers WITH vanilla's font providers
// (so a Nexo/Oraxen/ItemsAdder pack defining `minecraft:default` never shadows vanilla's `ascii.png`!),
// supports Nexo `<shift:N>` horizontal pixel offsets, per-run `<shadow:...>` colors, and pre-1.13 classic fonts.

import { buildFont, CLASSIC_ASCII_PROVIDER } from './pack.js'
import { DEFAULT_FONT, key as fontKey } from './fonts.js'
import { split } from './assets.js'
import { runs } from './mctext.js'

/**
 * Reads vanilla's font providers for [key] (always bypassing the resource pack so a pack's `default.json`
 * never shadows vanilla's `minecraft:include/default` / `ascii.png`!).
 */
async function vanillaProvidersOf(assets, key, seen = new Set()) {
  if (seen.has(key)) return []
  seen.add(key)
  const { ns, path } = split(key)
  // Pre-1.13 (pack_format < 4): Minecraft had no `font/default.json`, only `textures/font/ascii.png` + space = 4px.
  if (assets.versionInfo && !assets.versionInfo.hasFontJson && key === DEFAULT_FONT) {
    return [
      { type: 'space', advances: { ' ': 4 } },
      CLASSIC_ASCII_PROVIDER,
    ]
  }
  const readJson = typeof assets.vanillaJson === 'function'
    ? (n, f, p) => assets.vanillaJson(n, f, p)
    : (n, f, p) => assets.json(n, f, p)
  const json = await readJson(ns, 'font', path)
  const out = []
  for (const provider of json?.providers ?? []) {
    if (provider.type === 'reference') out.push(...(await vanillaProvidersOf(assets, fontKey(provider.id), seen)))
    else out.push(provider)
  }
  // Fallback if vanilla font json couldn't be read on a version where ascii.png still exists:
  if (out.length === 0 && key === DEFAULT_FONT && assets.vanilla) {
    return [
      { type: 'space', advances: { ' ': 4 } },
      CLASSIC_ASCII_PROVIDER,
    ]
  }
  return out
}

/** The darker color the game draws the shadow of a text with: a quarter of each channel. */
export function shadowOf(color) {
  const n = parseInt(color.slice(1), 16)
  const quarter = (v) => (v & 255) >> 2
  return `#${[quarter(n >> 16), quarter(n >> 8), quarter(n)].map((v) => v.toString(16).padStart(2, '0')).join('')}`
}

export class Text {
  /**
   * @param {import('./assets.js').Assets} assets where the game's files come from
   * @param {() => import('./pack.js').Pack|null} pack the resource pack that is loaded now, if any
   */
  constructor(assets, pack = () => null) {
    this.assets = assets
    this.pack = pack
    this.cache = new Map()
    /** Transforms text before layout (used for Nexo/Oraxen/ItemsAdder glyph & shift placeholders). */
    this.preprocess = (text) => text
  }

  /** The fonts to try for [key]: the pack's providers first, then vanilla's providers (with pack textures still overriding vanilla textures!). */
  fonts(key) {
    const pack = this.pack()
    const id = `${pack?.label ?? ''}|${this.assets.versionBranch ?? ''}|${key}`
    if (!this.cache.has(id)) {
      this.cache.set(id, (async () => {
        const list = []
        if (pack?.fontProviders.has(key)) list.push(await pack.font(key))
        const providers = await vanillaProvidersOf(this.assets, key)
        if (providers.length) {
          list.push(await buildFont(providers, async (file) => {
            const { ns, path } = split(file)
            // Textures still check pack first -> vanilla second so custom HD `ascii.png` in a pack works!
            const texture = await this.assets.texture(ns, path.replace(/\.png$/, ''))
            if (!texture) return null
            const canvas = new OffscreenCanvas(texture.width, texture.height)
            canvas.getContext('2d').putImageData(new ImageData(texture.data, texture.width, texture.height), 0, 0)
            return { image: canvas, width: texture.width, height: texture.height, pixels: texture.data }
          }, async (file) => {
            const { ns, path } = split(file)
            return this.assets.bytes(ns, path)
          }))
        }
        return list
      })())
    }
    return this.cache.get(id)
  }

  /** The width of [text] in pixels of the game. */
  async measure(text) {
    return (await this.layout(text)).width
  }

  /** The characters of [text] with the font that draws each, their position and style, and the total width. */
  async layout(text) {
    const parts = runs(this.preprocess(text))
    const fontsByKey = new Map()
    for (const run of parts) {
      if (run.shift) continue
      const k = fontKey(run.font ?? DEFAULT_FONT)
      if (!fontsByKey.has(k)) fontsByKey.set(k, await this.fonts(k))
    }
    // The fallback font (Unifont) is loaded once, only when a character is missing from all bitmap/space fonts.
    for (const run of parts) {
      if (run.shift) continue
      const fonts = fontsByKey.get(fontKey(run.font ?? DEFAULT_FONT))
      const lacking = [...run.text].some((c) => c >= ' ' && fonts.every((f) => f.advance(c) === null))
      if (lacking) await Promise.all(fonts.map((f) => f.loadLate()))
    }
    const placed = []
    let x = 0
    for (const run of parts) {
      if (typeof run.shift === 'number') {
        x += run.shift
        continue
      }
      const fonts = fontsByKey.get(fontKey(run.font ?? DEFAULT_FONT))
      for (const character of run.text) {
        const font = fonts.find((f) => f.advance(character) !== null)
        const advance = (font ? font.advance(character) : 6) + (run.bold ? 1 : 0)
        placed.push({ character, font, x, run, advance })
        x += advance
      }
    }
    return { placed, width: x }
  }

  /**
   * Draws [text] with the top left of its line at [x], [y] on [ctx].
   * @param {{scale?: number, color?: string, shadow?: boolean}} [options]
   * @returns {Promise<number>} where the text ends, in screen pixels
   */
  async draw(ctx, text, x, y, { scale = 3, color = '#ffffff', shadow = true } = {}) {
    const { placed, width } = await this.layout(text)
    const hasCustomShadow = placed.some((p) => typeof p.run.shadow === 'string')
    const passes = (shadow || hasCustomShadow) ? [true, false] : [false]
    for (const isShadow of passes) {
      for (const item of placed) {
        if (isShadow) {
          if (item.run.shadow === false) continue
          if (!shadow && typeof item.run.shadow !== 'string') continue
        }
        const tint = item.run.color ?? color
        const used = isShadow
          ? (typeof item.run.shadow === 'string' ? item.run.shadow : shadowOf(tint))
          : tint
        const at = x + item.x * scale + (isShadow ? scale : 0)
        const top = y + (isShadow ? scale : 0)
        this.glyph(ctx, item, at, top, scale, used)
        if (item.run.bold) this.glyph(ctx, item, at + scale, top, scale, used)
      }
    }
    this.lines(ctx, placed, x, y, scale, color, shadow)
    return x + width * scale
  }

  glyph(ctx, item, x, y, scale, color) {
    ctx.save()
    if (item.run.italic) {
      ctx.translate(x, y + 8 * scale)
      ctx.transform(1, 0, -0.125, 1, 0, 0)
      ctx.translate(-x, -(y + 8 * scale))
    }
    if (item.font) item.font.draw(ctx, item.character, x, y, scale, color)
    else {
      ctx.fillStyle = color
      ctx.font = `${8 * scale}px monospace`
      ctx.textBaseline = 'top'
      ctx.fillText(item.character, x, y)
    }
    ctx.restore()
  }

  lines(ctx, placed, x, y, scale, color, shadow) {
    for (const isShadow of shadow ? [true, false] : [false]) {
      for (const item of placed) {
        if (isShadow && item.run.shadow === false) continue
        const tint = item.run.color ?? color
        ctx.fillStyle = isShadow
          ? (typeof item.run.shadow === 'string' ? item.run.shadow : shadowOf(tint))
          : tint
        const offset = isShadow ? scale : 0
        if (item.run.underline) ctx.fillRect(x + item.x * scale + offset, y + 8 * scale + offset, item.advance * scale, scale)
        if (item.run.strike) ctx.fillRect(x + item.x * scale + offset, y + 4 * scale + offset, item.advance * scale, scale)
      }
    }
  }
}
