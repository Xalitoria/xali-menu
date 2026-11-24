// A resource pack loaded in the page: reads pack.mcmeta (pack_format & version overlays),
// fonts (to draw titles with glyphs), and item models/textures. Everything stays in the browser.

import { Zip } from './zip.js'
import { DEFAULT_FONT, readFonts, flatten, cells, spaces } from './fonts.js'
import { split, pickModel, modelTexture } from './models.js'
import { UnihexSet } from './unihex.js'
import { activeOverlays, parsePackMeta } from './versions.js'

/** Classic 16x16 `ascii.png` character table used by Minecraft 1.8 – 1.12.2 before `font/default.json` existed. */
export const CLASSIC_ASCII_PROVIDER = {
  type: 'bitmap',
  file: 'minecraft:font/ascii.png',
  ascent: 7,
  height: 8,
  chars: [
    '\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000',
    '\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000',
    ' !"#$%&\'()*+,-./',
    '0123456789:;<=>?',
    '@ABCDEFGHIJKLMNO',
    'PQRSTUVWXYZ[\\]^_',
    '`abcdefghijklmno',
    'pqrstuvwxyz{|}~\u0000',
  ],
}

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
    this.late = late
  }

  loadLate() { return this.late ? this.late.load() : Promise.resolve() }

  glyphOf(character) { return this.glyphs.get(character) ?? this.late?.get(character) ?? null }

  advance(character) {
    return this.spaces.get(character) ?? this.glyphOf(character)?.advance ?? null
  }

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
  /** @param {Zip} zip @param {Map<string, any[]>} fonts @param {string} label @param {object} [meta] */
  constructor(zip, fonts, label, meta = null) {
    this.zip = zip
    this.fontProviders = fonts
    this.label = label
    this.meta = meta ?? parsePackMeta(null)
    this.packFormat = this.meta.packFormat
    this.fontCache = new Map()
    this.urlCache = new Map()
    this.namespaces = [...new Set(zip.list().map((p) => p.match(/^(?:[^/]+\/)?assets\/([^/]+)\//)?.[1]).filter(Boolean))].sort()
  }

  static async load(blob, label = 'resource pack', targetPackFormat = null) {
    const zip = await Zip.open(blob)
    let meta = parsePackMeta(null)
    if (zip.has('pack.mcmeta')) {
      try { meta = parsePackMeta(await zip.json('pack.mcmeta')) } catch { /* ignore invalid mcmeta */ }
    }
    const effectiveFormat = targetPackFormat ?? meta.packFormat
    const overlays = activeOverlays(meta, effectiveFormat)
    const fonts = await readFontsWithOverlays(zip, overlays)
    return new Pack(zip, fonts, label, meta)
  }

  /** Resolves [assetPath] (`assets/...`) through active overlays matching [targetPackFormat], then pack root. */
  resolvePath(assetPath, targetPackFormat = this.packFormat) {
    const overlays = activeOverlays(this.meta, targetPackFormat)
    for (const dir of overlays) {
      const candidate = `${dir}/${assetPath}`
      if (this.zip.has(candidate)) return candidate
    }
    return this.zip.has(assetPath) ? assetPath : null
  }

  has(assetPath, targetPackFormat = this.packFormat) {
    return this.resolvePath(assetPath, targetPackFormat) !== null
  }

  async readJson(assetPath, targetPackFormat = this.packFormat) {
    const resolved = this.resolvePath(assetPath, targetPackFormat)
    return resolved ? this.zip.json(resolved) : null
  }

  async readBytes(assetPath, targetPackFormat = this.packFormat) {
    const resolved = this.resolvePath(assetPath, targetPackFormat)
    return resolved ? this.zip.bytes(resolved) : null
  }

  fontKeys() { return [...this.fontProviders.keys()].sort() }

  font(fontKey = DEFAULT_FONT) {
    if (!this.fontCache.has(fontKey)) this.fontCache.set(fontKey, this.buildFont(fontKey))
    return this.fontCache.get(fontKey)
  }

  async buildFont(fontKey) {
    const providers = flatten(this.fontProviders, fontKey)
    return buildFont(providers, async (file) => {
      const { ns, path } = split(file)
      const image = await this.image(`assets/${ns}/textures/${path}`)
      return image ? { image, width: image.width, height: image.height, pixels: readPixels(image) } : null
    }, async (file) => {
      const { ns, path } = split(file)
      return this.readBytes(`assets/${ns}/${path}`)
    })
  }

  async image(path) {
    const resolved = this.resolvePath(path)
    if (!resolved) return null
    try { return await createImageBitmap(new Blob([await this.zip.bytes(resolved)], { type: 'image/png' })) } catch { return null }
  }

  async url(path) {
    const resolved = this.resolvePath(path)
    if (!resolved) return null
    if (!this.urlCache.has(resolved)) this.urlCache.set(resolved, this.zip.bytes(resolved).then((b) => URL.createObjectURL(new Blob([b], { type: 'image/png' }))))
    return this.urlCache.get(resolved)
  }

  itemTextures(id, extra = {}) {
    const base = extra.material ?? id
    const candidates = []
    if (extra.itemModel) candidates.push(this.fromDefinition(extra.itemModel, extra))
    candidates.push(this.fromDefinition(`minecraft:${base}`, extra))
    candidates.push(this.url(`assets/minecraft/textures/item/${base}.png`))
    candidates.push(this.url(`assets/minecraft/textures/items/${base}.png`))
    return candidates
  }

  async fromDefinition(itemKey, extra) {
    const { ns, path } = split(itemKey)
    const def = await this.readJson(`assets/${ns}/items/${path}.json`)
    if (!def) return null
    const model = pickModel(def, { customModelData: extra.customModelData })
    if (!model) return null
    const read = async (modelNs, modelPath) => this.readJson(`assets/${modelNs}/models/${modelPath}.json`)
    const texture = await modelTexture(read, model)
    if (!texture) return null
    const found = split(texture)
    return (await this.url(`assets/${found.ns}/textures/${found.path}.png`)) ?? this.url(`assets/${found.ns}/textures/${found.path.replace(/^item\//, 'items/').replace(/^block\//, 'blocks/')}.png`)
  }

  async summary() {
    const providers = [...this.fontProviders.keys()].map((k) => flatten(this.fontProviders, k)).flat()
    return {
      namespaces: this.namespaces,
      fonts: this.fontProviders.size,
      bitmaps: providers.filter((p) => p.type === 'bitmap').length,
      packFormat: this.packFormat,
      overlays: this.meta.overlays.length,
    }
  }
}

/** Reads fonts from the pack root plus any active version overlays (overlay providers take priority). */
async function readFontsWithOverlays(zip, overlays) {
  const base = await readFonts(zip)
  if (!overlays || overlays.length === 0) return base
  const merged = new Map()
  // Overlays are ordered highest-priority first; prepend overlay providers before base providers.
  for (const dir of overlays) {
    const prefix = `${dir}/assets/`
    for (const path of zip.list()) {
      if (!path.startsWith(prefix)) continue
      const match = path.slice(dir.length + 1).match(/^assets\/([^/]+)\/font\/(.+)\.json$/)
      if (!match) continue
      try {
        const key = `${match[1]}:${match[2]}`
        const providers = (await zip.json(path)).providers ?? []
        merged.set(key, [...(merged.get(key) ?? []), ...providers])
      } catch { /* ignore invalid font json */ }
    }
  }
  for (const [key, providers] of base.entries()) {
    merged.set(key, [...(merged.get(key) ?? []), ...providers])
  }
  return merged
}

export async function buildFont(providers, load, loadBytes = null) {
  const glyphs = new Map()
  for (const provider of providers) {
    if (provider.type !== 'bitmap' || !provider.file) continue
    const loaded = await load(provider.file)
    if (!loaded) continue
    const rows = provider.chars.map(cells)
    const columns = Math.max(...rows.map((r) => r.length))
    if (columns <= 0 || rows.length <= 0) continue
    const sw = Math.floor(loaded.width / columns)
    const sh = Math.floor(loaded.height / rows.length)
    if (sw <= 0 || sh <= 0) continue
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

function visibleWidth(pixels, stride, x0, y0, width, height) {
  for (let x = width - 1; x >= 0; x--) {
    for (let y = 0; y < height; y++) if (pixels[((y0 + y) * stride + x0 + x) * 4 + 3] > 0) return x + 1
  }
  return 0
}
