// Where the files of the game come from. The resource pack of the server is asked first, then the game's own files, which are
// read from a public copy of Mojang's assets for the version of the server (they are not part of this editor). Each file is
// fetched once, and a file that is not there is remembered as not there.

/** The copy of the game's assets, one branch per version. The page can change both. */
export const settings = {
  base: 'https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets',
  version: '26.2',
  /** Fetches a skin by its address through the server, since Mojang's texture server does not let other sites read it. Set when the editor is connected. */
  skinProxy: null,
}

/** The pixels of a texture: RGBA, row by row. */
export class Texture {
  constructor(width, height, data) {
    this.width = width
    this.height = height
    this.data = data
  }
}

/** `ns:path` and `path` both mean a file; a bare path is in the `minecraft` namespace. */
export function split(key) {
  const colon = key.indexOf(':')
  return colon < 0 ? { ns: 'minecraft', path: key } : { ns: key.slice(0, colon), path: key.slice(colon + 1) }
}

export class Assets {
  /**
   * @param {{pack?: import('./pack.js').Pack|null, vanilla?: boolean}} [options]
   *   [pack] is asked first; [vanilla] false keeps the game's own files out (tests, or offline)
   */
  constructor({ pack = null, vanilla = true } = {}) {
    this.pack = pack
    this.vanilla = vanilla
    this.cache = new Map()
  }

  /** The json at assets/<ns>/<folder>/<path>.json, or null if neither the pack nor the game has it. */
  json(ns, folder, path) {
    return this.cached(`json:${ns}:${folder}/${path}`, async () => {
      const file = `assets/${ns}/${folder}/${path}.json`
      if (this.pack?.zip.has(file)) return this.pack.zip.json(file)
      return this.fetchGame(ns, file, (response) => response.json())
    })
  }

  /** The texture at assets/<ns>/textures/<path>.png, as pixels, or null. */
  texture(ns, path) {
    return this.cached(`texture:${ns}:${path}`, async () => {
      const file = `assets/${ns}/textures/${path}.png`
      const blob = this.pack?.zip.has(file)
        ? new Blob([await this.pack.zip.bytes(file)], { type: 'image/png' })
        : await this.fetchGame(ns, file, (response) => response.blob())
      if (!blob) return null
      return decode(blob, await this.meta(ns, path))
    })
  }

  /** The `.png.mcmeta` of a texture: whether and how it is animated. */
  meta(ns, path) {
    return this.cached(`meta:${ns}:${path}`, async () => {
      const file = `assets/${ns}/textures/${path}.png.mcmeta`
      if (this.pack?.zip.has(file)) return this.pack.zip.json(file)
      return this.fetchGame(ns, file, (response) => response.json())
    })
  }

  /** A texture from any address (the skin of a head), or null if the host does not let this page read it. */
  fromUrl(url) {
    return this.cached(`url:${url}`, async () => {
      try {
        const response = await fetch(url)
        if (response.ok) return skin(await decode(await response.blob(), null))
      } catch { /* not readable from here: try through the server */ }
      const blob = settings.skinProxy ? await settings.skinProxy(url).catch(() => null) : null
      return blob ? skin(await decode(blob, null)) : null
    })
  }

  /** The bytes of any file of the game or the pack (a font's unihex zip, for instance), or null. */
  bytes(ns, file) {
    return this.cached(`bytes:${ns}:${file}`, async () => {
      const name = `assets/${ns}/${file}`
      if (this.pack?.zip.has(name)) return this.pack.zip.bytes(name)
      return this.fetchGame(ns, name, async (response) => new Uint8Array(await response.arrayBuffer()))
    })
  }

  async fetchGame(ns, file, read) {
    if (!this.vanilla || ns !== 'minecraft') return null
    try {
      const response = await fetch(`${settings.base}/${settings.version}/${file}`)
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

/**
 * A skin the way the game prepares it: an old skin (64 by 32) is put on a 64 by 64 sheet, the base layer of the head is made opaque, and
 * a hat layer that has no transparent pixel at all (old skins have a solid one) is made fully transparent.
 * @param {Texture} texture
 * @returns {Texture}
 */
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
    // The solid hat of an old skin: if nothing in the area is transparent, none of it is shown.
    let solid = true
    for (let y = 0; y < 16 && solid; y++) for (let x = 32; x < 64; x++) if (data[(y * width + x) * 4 + 3] < 128) { solid = false; break }
    if (solid) for (let y = 0; y < 16; y++) for (let x = 32; x < 64; x++) data[(y * width + x) * 4 + 3] = 0
  }
  opaque(0, 0, 32, 16)
  return new Texture(width, height, data)
}

/** Decodes a png into pixels. An animated texture is a strip of frames; the first one is shown, as in the game for an item that is not animated by time. */
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
