// A resource pack loaded in the page: its fonts (to draw titles with glyphs) and its item textures (to draw the items).
// Everything is read from the zip when first needed and kept. The pack never leaves the browser.

import { Zip } from './zip.js'
import { DEFAULT_FONT, readFonts, flatten, cells, spaces, key } from './fonts.js'
import { split, pickModel, modelTexture } from './models.js'
import { UnihexSet } from './unihex.js'

/** One character of a font that is a picture. [advance] is how far it moves the text on, in pixels of the game's font (8 high). */
export class Glyph {
  constructor(image, sx, sy, sw, sh, ascent, height, advance) {
    Object.assign(this, { image, sx, sy, sw, sh, ascent, height, advance })
    this.scale = height / sh
  }
}

export class Font {
  /** @param {Map<string, Glyph>} glyphs @param {Map<string, number>} spaces */
  constructor(glyphs, spaces, late = null) {
    this.glyphs = glyphs
    this.spaces = spaces
    /** The glyphs of `unihex` providers, which are read only when a character nothing else has is asked for. */
    this.late = late
  }

  /** Reads what is kept back (the fallback font), if there is any. */
  loadLate() { return this.late ? this.late.load() : Promise.resolve() }

  glyphOf(character) { return this.glyphs.get(character) ?? this.late?.get(character) ?? null }

  /** How far the character moves the text on, or null if the font does not have it. */
  advance(character) {
    return this.spaces.get(character) ?? this.glyphOf(character)?.advance ?? null
  }

  /**
   * Draws [character] with its top left at [x], [lineTop] (the top of the line of text), all multiplied by [scale] and tinted by
   * [color]. Returns the advance in pixels of the game, or null if the font does not have the character.
   */
  draw(ctx, character, x, lineTop, scale, color) {
    const space = this.spaces.get(character)
    if (space !== undefined) return space
    const glyph = this.glyphOf(character)
    if (!glyph) return null
    const width = glyph.sw * glyph.scale * scale
    const height = glyph.sh * glyph.scale * scale
    const top = lineTop + (7 - glyph.ascent) * scale
    const layer = new OffscreenCanvas(Math.max(1, Math.ceil(width)), Math.max(1, Math.ceil(height)))
    const l = layer.getContext('2d')
    l.imageSmoothingEnabled = false
    l.drawImage(glyph.image, glyph.sx, glyph.sy, glyph.sw, glyph.sh, 0, 0, width, height)
    if (color && color !== '#ffffff') {
      l.globalCompositeOperation = 'multiply'
      l.fillStyle = color
      l.fillRect(0, 0, layer.width, layer.height)
      l.globalCompositeOperation = 'destination-in'
      l.drawImage(glyph.image, glyph.sx, glyph.sy, glyph.sw, glyph.sh, 0, 0, width, height)
    }
    ctx.imageSmoothingEnabled = false
    ctx.drawImage(layer, x, top)
    return glyph.advance
  }
}

export class Pack {
  /** @param {Zip} zip @param {Map<string, any[]>} fonts @param {string} label */
  constructor(zip, fonts, label) {
    this.zip = zip
    this.fontProviders = fonts
    this.label = label
    this.fontCache = new Map()
    this.urlCache = new Map()
    this.namespaces = [...new Set(zip.list().map((p) => p.match(/^assets\/([^/]+)\//)?.[1]).filter(Boolean))].sort()
  }

  static async load(blob, label = 'resource pack') {
    const zip = await Zip.open(blob)
    return new Pack(zip, await readFonts(zip), label)
  }

  /** The keys of the fonts the pack defines. */
  fontKeys() { return [...this.fontProviders.keys()].sort() }

  /** @returns {Promise<Font>} */
  font(fontKey = DEFAULT_FONT) {
    if (!this.fontCache.has(fontKey)) this.fontCache.set(fontKey, this.buildFont(fontKey))
    return this.fontCache.get(fontKey)
  }

  async buildFont(fontKey) {
    const providers = flatten(this.fontProviders, fontKey)
    return buildFont(providers, async (file) => {
      const image = await this.image(`assets/${split(file).ns}/textures/${split(file).path}`)
      return image ? { image, width: image.width, height: image.height, pixels: readPixels(image) } : null
    }, async (file) => {
      const name = `assets/${split(file).ns}/${split(file).path}`
      return this.zip.has(name) ? this.zip.bytes(name) : null
    })
  }

  /** The decoded picture at [path] of the zip, or null. */
  async image(path) {
    if (!this.zip.has(path)) return null
    try { return await createImageBitmap(new Blob([await this.zip.bytes(path)], { type: 'image/png' })) } catch { return null }
  }

  /** A URL for the file at [path] of the zip, or null if it is not there. */
  async url(path) {
    if (!this.zip.has(path)) return null
    if (!this.urlCache.has(path)) this.urlCache.set(path, this.zip.bytes(path).then((b) => URL.createObjectURL(new Blob([b], { type: 'image/png' }))))
    return this.urlCache.get(path)
  }

  /**
   * Candidates for the picture of an item, as promises of URLs (null where the pack has nothing), best first. [id] is the item or
   * the id of a custom item; [extra] says what the plugin that owns it resolved it to: `itemModel` and `customModelData`.
   * @param {string} id
   * @param {{itemModel?: string, customModelData?: number[], material?: string}} extra
   */
  itemTextures(id, extra = {}) {
    const base = extra.material ?? id
    const candidates = []
    if (extra.itemModel) candidates.push(this.fromDefinition(extra.itemModel, extra))
    candidates.push(this.fromDefinition(`minecraft:${base}`, extra))
    candidates.push(this.url(`assets/minecraft/textures/item/${base}.png`))
    return candidates
  }

  /** The texture an item definition leads to, as a URL. */
  async fromDefinition(itemKey, extra) {
    const { ns, path } = split(itemKey)
    const file = `assets/${ns}/items/${path}.json`
    if (!this.zip.has(file)) return null
    const model = pickModel(await this.zip.json(file), { customModelData: extra.customModelData })
    if (!model) return null
    const read = async (modelNs, modelPath) => {
      const name = `assets/${modelNs}/models/${modelPath}.json`
      return this.zip.has(name) ? this.zip.json(name) : null
    }
    const texture = await modelTexture(read, model)
    if (!texture) return null
    const found = split(texture)
    return this.url(`assets/${found.ns}/textures/${found.path}.png`)
  }

  /** Counts, for the summary the page shows. */
  async summary() {
    const providers = [...this.fontProviders.keys()].map((k) => flatten(this.fontProviders, k)).flat()
    return { namespaces: this.namespaces, fonts: this.fontProviders.size, bitmaps: providers.filter((p) => p.type === 'bitmap').length }
  }
}

/**
 * A font from its flattened providers: the bitmaps become glyphs, the spaces advances. [load] gives the picture, its size and its pixels for
 * the file of a bitmap provider (`ns:path`), or null if there is none.
 * @param {any[]} providers
 * @param {(file: string) => Promise<{image: CanvasImageSource, width: number, height: number, pixels: Uint8ClampedArray}|null>} load
 */
export async function buildFont(providers, load, loadBytes = null) {
  const glyphs = new Map()
  for (const provider of providers) {
    if (provider.type !== 'bitmap' || !provider.file) continue
    const loaded = await load(provider.file)
    if (!loaded) continue
    const rows = provider.chars.map(cells)
    const columns = Math.max(...rows.map((r) => r.length))
    const sw = Math.floor(loaded.width / columns)
    const sh = Math.floor(loaded.height / rows.length)
    const height = provider.height ?? 8
    const scale = height / sh
    rows.forEach((row, r) => row.forEach((character, c) => {
      if (character === '\u0000' || glyphs.has(character)) return
      const width = visibleWidth(loaded.pixels, loaded.width, c * sw, r * sh, sw, sh)
      glyphs.set(character, new Glyph(loaded.image, c * sw, r * sh, sw, sh, provider.ascent ?? 0, height, Math.round(width * scale) + 1))
    }))
  }
  const hex = providers.filter((p) => p.type === 'unihex' && p.hex_file)
  const late = hex.length && loadBytes ? new UnihexSet(hex, loadBytes, (picture, advance) => {
    const canvas = new OffscreenCanvas(picture.width, picture.height)
    canvas.getContext('2d').putImageData(new ImageData(picture.data, picture.width, picture.height), 0, 0)
    return new Glyph(canvas, 0, 0, picture.width, picture.height, 7, 8, advance)
  }) : null
  return new Font(glyphs, spaces(providers), late)
}

function readPixels(image) {
  const canvas = new OffscreenCanvas(image.width, image.height)
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  ctx.drawImage(image, 0, 0)
  return ctx.getImageData(0, 0, image.width, image.height).data
}

/** The width of a cell up to its last column that has a visible pixel. */
function visibleWidth(pixels, stride, x0, y0, width, height) {
  for (let x = width - 1; x >= 0; x--) {
    for (let y = 0; y < height; y++) if (pixels[((y0 + y) * stride + x0 + x) * 4 + 3] > 0) return x + 1
  }
  return 0
}
