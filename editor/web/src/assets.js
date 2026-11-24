// Version- and overlay-aware Asset Manager shared by XaliMenu and XaliCrates web editors.
// Resolves files in Minecraft's exact resource-pack layering order:
//   1. Pack overlays matching the active server `packFormat` (highest priority first)
//   2. Pack root `assets/...`
//   3. Vanilla Minecraft assets for the active server version branch
//
// Explicit `vanillaJson` / `vanillaTexture` / `vanillaBytes` methods bypass the pack so systems that
// MERGE pack + vanilla definitions (like `font/*.json`) never get shadowed by a pack's `default.json`!

import { LATEST_VERSION, resolveVersion } from './versions.js'

/** Global asset source configuration; updated automatically when connected to a server. */
export const settings = {
  base: 'https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets',
  version: LATEST_VERSION,
  /** Fetches a skin by its address through the server (Mojang's texture host lacks CORS). */
  skinProxy: null,
  /** Fetches a player's skin by username through the server (online / SkinsRestorer / Mojang). */
  playerSkinProxy: null,
  /** Fetches a game font zip (e.g. `font/unifont.zip`) through the server. */
  gameFile: null,
}

/** Updates the active Minecraft version for vanilla asset fetches and returns the resolved version info. */
export function setMinecraftVersion(versionOrFormat) {
  const info = resolveVersion(versionOrFormat)
  settings.version = info.branch
  return info
}

/** The pixels of a texture: RGBA, row by row. */
export class Texture {
  constructor(width, height, data) {
    this.width = width
    this.height = height
    this.data = data
  }
}

/** Parses [text] as JSON, forgiving trailing commas before a closing bracket. */
export function lenient(text) {
  try { return JSON.parse(text) } catch (error) {
    const repaired = text.replace(/,(\s*[\]}])/g, '$1')
    if (repaired === text) throw error
    return JSON.parse(repaired)
  }
}

/** Parses a pre-1.13 `en_us.lang` (`key=value`) file into a JSON-like object. */
export function parseLegacyLang(text) {
  const out = {}
  for (const line of String(text ?? '').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const eq = trimmed.indexOf('=')
    if (eq > 0) out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1)
  }
  return out
}

/** `ns:path` and `path` both mean a file; a bare path is in the `minecraft` namespace. */
export function split(key) {
  const colon = key.indexOf(':')
  return colon < 0 ? { ns: 'minecraft', path: key } : { ns: key.slice(0, colon), path: key.slice(colon + 1) }
}

/** Alternate legacy/modern texture paths (1.12 used `items/` and `blocks/`; 1.13+ uses `item/` and `block/`). */
function texturePathVariants(path, preferLegacy) {
  const variants = [path]
  if (path.startsWith('item/')) {
    const alt = `items/${path.slice(5)}`
    if (preferLegacy) variants.unshift(alt)
    else variants.push(alt)
  } else if (path.startsWith('items/')) {
    variants.push(`item/${path.slice(6)}`)
  } else if (path.startsWith('block/')) {
    const alt = `blocks/${path.slice(6)}`
    if (preferLegacy) variants.unshift(alt)
    else variants.push(alt)
  } else if (path.startsWith('blocks/')) {
    variants.push(`block/${path.slice(7)}`)
  }
  return variants
}

export class Assets {
  /**
   * @param {{pack?: import('./pack.js').Pack|null, vanilla?: boolean, version?: string|number|null}} [options]
   */
  constructor({ pack = null, vanilla = true, version = null } = {}) {
    this.pack = pack
    this.vanilla = vanilla
    this.versionInfo = resolveVersion(version ?? settings.version)
    this.cache = new Map()
  }

  get packFormat() { return this.versionInfo.packFormat }
  get versionBranch() { return this.versionInfo.branch }

  /** Checks if the active pack (including overlays matching `this.packFormat`) contains [file]. */
  packHas(file) {
    if (!this.pack) return false
    return typeof this.pack.has === 'function' ? this.pack.has(file, this.packFormat) : this.pack.zip.has(file)
  }

  packJson(file) {
    return typeof this.pack.readJson === 'function' ? this.pack.readJson(file, this.packFormat) : this.pack.zip.json(file)
  }

  packBytes(file) {
    return typeof this.pack.readBytes === 'function' ? this.pack.readBytes(file, this.packFormat) : this.pack.zip.bytes(file)
  }

  /** The json at assets/<ns>/<folder>/<path>.json (pack overlays -> pack -> vanilla). */
  json(ns, folder, path) {
    return this.cached(`json:${ns}:${folder}/${path}`, async () => {
      const file = `assets/${ns}/${folder}/${path}.json`
      if (this.packHas(file)) return this.packJson(file)
      const fetched = await this.fetchGame(ns, file, async (response) => lenient(await response.text()))
      if (fetched) return fetched
      // Pre-1.13 language files were `.lang` (`en_us.lang`) instead of `.json`.
      if (folder === 'lang' && !this.versionInfo.hasLangJson) {
        const langFile = `assets/${ns}/lang/${path}.lang`
        if (this.packHas(langFile)) {
          const bytes = await this.packBytes(langFile)
          return parseLegacyLang(new TextDecoder().decode(bytes))
        }
        return this.fetchGame(ns, langFile, async (response) => parseLegacyLang(await response.text()))
      }
      return null
    })
  }

  /** Vanilla-only JSON (bypasses the resource pack; used when merging pack + vanilla font providers). */
  vanillaJson(ns, folder, path) {
    return this.cached(`vanilla-json:${ns}:${folder}/${path}`, async () => {
      const file = `assets/${ns}/${folder}/${path}.json`
      return this.fetchGame(ns, file, async (response) => lenient(await response.text()))
    })
  }

  /** The texture at assets/<ns>/textures/<path>.png, as pixels, or null (supports 1.8–26.x texture paths). */
  texture(ns, path) {
    return this.cached(`texture:${ns}:${path}`, async () => {
      const candidates = texturePathVariants(path, this.versionInfo.hasLegacyTextureDirs)
      for (const candidate of candidates) {
        const file = `assets/${ns}/textures/${candidate}.png`
        const blob = this.packHas(file)
          ? new Blob([await this.packBytes(file)], { type: 'image/png' })
          : await this.fetchGame(ns, file, (response) => response.blob())
        if (blob) return decode(blob, await this.meta(ns, candidate))
      }
      return this.permuted(ns, path)
    })
  }

  /** The `paletted_permutations` of the atlases of the item textures: the pack's and the game's. */
  permutations() {
    return this.cached('permutations', async () => {
      if (!this.versionInfo.hasPalettedPermutations) return []
      const found = []
      for (const name of ['items', 'blocks', 'armor_trims']) {
        const file = `assets/minecraft/atlases/${name}.json`
        const documents = []
        if (this.packHas(file)) documents.push(await this.packJson(file))
        documents.push(await this.fetchGame('minecraft', file, async (response) => lenient(await response.text())))
        for (const document of documents) for (const source of document?.sources ?? []) if (/paletted_permutations$/.test(source.type ?? '')) found.push(source)
      }
      return found
    })
  }

  async permuted(ns, path) {
    for (const source of await this.permutations()) {
      for (const texture of source.textures ?? []) {
        const base = split(texture)
        if (base.ns !== ns || !path.startsWith(`${base.path}_`)) continue
        const palette = source.permutations?.[path.slice(base.path.length + 1)]
        if (!palette) continue
        const [grey, keys, colors] = await Promise.all([this.texture(base.ns, base.path), this.paletteOf(source.palette_key), this.paletteOf(palette)])
        return grey && keys && colors ? recolor(grey, keys, colors) : null
      }
    }
    return null
  }

  paletteOf(key) {
    const { ns, path } = split(key)
    return this.texture(ns, path)
  }

  /** The `.png.mcmeta` of a texture: whether and how it is animated. */
  meta(ns, path) {
    return this.cached(`meta:${ns}:${path}`, async () => {
      const file = `assets/${ns}/textures/${path}.png.mcmeta`
      if (this.packHas(file)) return this.packJson(file)
      return this.fetchGame(ns, file, async (response) => lenient(await response.text()))
    })
  }

  /** A texture from any address (the skin of a head), or null if blocked. */
  fromUrl(url) {
    return this.cached(`url:${url}`, async () => {
      try {
        const response = await fetch(url)
        if (response.ok) return skin(await decode(await response.blob(), null))
      } catch { /* try through server proxy */ }
      const blob = settings.skinProxy ? await settings.skinProxy(url).catch(() => null) : null
      return blob ? skin(await decode(blob, null)) : null
    })
  }

  /** A player's skin texture by username: server proxy first, then mc-heads.net. */
  fromPlayer(name) {
    const clean = String(name ?? '').trim()
    if (!clean) return Promise.resolve(null)
    return this.cached(`player:${clean.toLowerCase()}`, async () => {
      if (settings.playerSkinProxy) {
        const blob = await settings.playerSkinProxy(clean).catch(() => null)
        if (blob) return skin(await decode(blob, null))
      }
      try {
        const response = await fetch(`https://mc-heads.net/skin/${encodeURIComponent(clean)}`)
        if (response.ok) return skin(await decode(await response.blob(), null))
      } catch { /* offline */ }
      return null
    })
  }

  /** The bytes of any file of the game or the pack (a font's unihex zip, for instance), or null. */
  bytes(ns, file) {
    return this.cached(`bytes:${ns}:${file}`, async () => {
      const name = `assets/${ns}/${file}`
      if (this.packHas(name)) return this.packBytes(name)
      const found = await this.fetchGame(ns, name, async (response) => new Uint8Array(await response.arrayBuffer()))
      if (found || ns !== 'minecraft' || !settings.gameFile) return found
      return settings.gameFile(file).catch(() => null)
    })
  }

  /** Vanilla-only bytes (bypasses the pack). */
  vanillaBytes(ns, file) {
    return this.cached(`vanilla-bytes:${ns}:${file}`, async () => {
      const name = `assets/${ns}/${file}`
      const found = await this.fetchGame(ns, name, async (response) => new Uint8Array(await response.arrayBuffer()))
      if (found || ns !== 'minecraft' || !settings.gameFile) return found
      return settings.gameFile(file).catch(() => null)
    })
  }

  async fetchGame(ns, file, read) {
    if (!this.vanilla || ns !== 'minecraft') return null
    try {
      const response = await fetch(`${settings.base}/${this.versionBranch}/${file}`)
      return response.ok ? await read(response) : null
    } catch {
      return null
    }
  }

  cached(key, make) {
    if (!this.cache.has(key)) this.cache.set(key, make().catch(() => null))
    return this.cache.get(key)
  }
}

export function skin(texture) {
  let { width, height, data } = texture
  if (height === 32 && width === 64) {
    const grown = new Uint8ClampedArray(64 * 64 * 4)
    grown.set(data)
    data = grown
    height = 64
  }
  const opaque = (x0, y0, x1, y1) => { for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) data[(y * width + x) * 4 + 3] = 255 }
  if (texture.height === 32) {
    let solid = true
    for (let y = 0; y < 16 && solid; y++) for (let x = 32; x < 64; x++) if (data[(y * width + x) * 4 + 3] < 128) { solid = false; break }
    if (solid) for (let y = 0; y < 16; y++) for (let x = 32; x < 64; x++) data[(y * width + x) * 4 + 3] = 0
  }
  opaque(0, 0, 32, 16)
  return new Texture(width, height, data)
}

async function decode(blob, meta) {
  const bitmap = await createImageBitmap(blob)
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height)
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  ctx.drawImage(bitmap, 0, 0)
  const animation = meta?.animation
  const height = animation ? Math.min(bitmap.height, animation.height ?? animation.width ?? bitmap.width) : bitmap.height
  const width = animation ? Math.min(bitmap.width, animation.width ?? bitmap.width) : bitmap.width
  return new Texture(width, height, ctx.getImageData(0, 0, width, height).data)
}

export function recolor(texture, keys, colors) {
  const map = new Map()
  for (let i = 0; i < keys.width * keys.height && i < colors.width * colors.height; i++) {
    if (keys.data[i * 4 + 3] === 0) continue
    map.set((keys.data[i * 4] << 16) | (keys.data[i * 4 + 1] << 8) | keys.data[i * 4 + 2], [colors.data[i * 4], colors.data[i * 4 + 1], colors.data[i * 4 + 2]])
  }
  const data = new Uint8ClampedArray(texture.data)
  for (let i = 0; i < data.length; i += 4) {
    if (data[i + 3] === 0) continue
    const to = map.get((data[i] << 16) | (data[i + 1] << 8) | data[i + 2])
    if (to) { data[i] = to[0]; data[i + 1] = to[1]; data[i + 2] = to[2] }
  }
  return new Texture(texture.width, texture.height, data)
}
