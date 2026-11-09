// The game's fallback font for characters its own pictures lack (GNU Unifont): a `unihex` provider names zip files of `.hex` lines,
// `<code point>:<rows>`, each glyph 16 rows high and 8, 16, 24 or 32 bits wide, one bit a pixel and the leftmost bit first. A glyph is drawn
// at half size (8 pixels of the game high), advances by half its width plus one, and `size_overrides` cut a range of glyphs to the columns
// from `left` to `right`. Without an override the columns with something in them are kept.

/** The glyphs of the `.hex` text, as code point to its hex digits. @returns {Map<number, string>} */
export function parseHex(text) {
  const glyphs = new Map()
  for (const line of text.split('\n')) {
    const colon = line.indexOf(':')
    if (colon < 1) continue
    const code = parseInt(line.slice(0, colon), 16)
    const rows = line.slice(colon + 1).trim()
    if (Number.isNaN(code) || rows.length === 0 || rows.length % 16 !== 0) continue
    glyphs.set(code, rows)
  }
  return glyphs
}

/** Whether the pixel at [column] of [row] is set. [hex] is the glyph's digits, [digits] the digits of a row. */
function pixel(hex, digits, row, column) {
  const digit = parseInt(hex[row * digits + (column >> 2)], 16)
  return ((digit >> (3 - (column & 3))) & 1) === 1
}

/**
 * Where the glyph's columns start and end, and how far it moves the text on.
 * @param {string} hex the glyph's digits
 * @param {{from: number, to: number, left: number, right: number}[]} overrides the provider's `size_overrides`, with `from` and `to` as numbers
 */
export function measure(hex, code, overrides = []) {
  const digits = hex.length / 16
  const bits = digits * 4
  let left = 0
  let right = bits - 1
  const override = overrides.find((o) => code >= o.from && code <= o.to)
  if (override) {
    left = override.left
    right = override.right
  } else {
    let first = -1
    let last = -1
    for (let column = 0; column < bits; column++) {
      for (let row = 0; row < 16; row++) {
        if (pixel(hex, digits, row, column)) {
          if (first < 0) first = column
          last = column
          break
        }
      }
    }
    if (first >= 0) { left = first; right = last }
  }
  return { left, right, advance: Math.floor((right - left + 1) / 2) + 1 }
}

/** The RGBA pixels of the columns [left] to [right] of the glyph, 16 rows, white where the bit is set. */
export function pixels(hex, left, right) {
  const digits = hex.length / 16
  const width = right - left + 1
  const data = new Uint8ClampedArray(width * 16 * 4)
  for (let row = 0; row < 16; row++) {
    for (let x = 0; x < width; x++) {
      if (left + x < digits * 4 && pixel(hex, digits, row, left + x)) data.fill(255, (row * width + x) * 4, (row * width + x) * 4 + 4)
    }
  }
  return { width, height: 16, data }
}

/** `size_overrides` as the numbers the game reads: `from` and `to` are characters. */
export function readOverrides(list = []) {
  return list.map((o) => ({
    from: typeof o.from === 'string' ? o.from.codePointAt(0) : o.from,
    to: typeof o.to === 'string' ? o.to.codePointAt(0) : o.to,
    left: o.left, right: o.right,
  }))
}

/** The glyphs of some `unihex` providers, read from their zip files when first asked, in the order the providers are listed. */
export class UnihexSet {
  /**
   * @param {{hex_file: string, size_overrides?: any[]}[]} providers
   * @param {(file: string) => Promise<Uint8Array|null>} loadBytes the bytes of a zip file of the game or pack
   * @param {(entries: Map<number, string>) => any} makeGlyph turns pixels into what the font draws: given [{width, height, data}, advance]
   */
  constructor(providers, loadBytes, makeGlyph) {
    this.providers = providers
    this.loadBytes = loadBytes
    this.makeGlyph = makeGlyph
    this.files = []
    this.cache = new Map()
    this.loaded = null
    this.done = false
  }

  /** Reads the zip files. Characters are known only after this has finished. */
  load() {
    this.loaded ??= (async () => {
      for (const provider of this.providers) {
        try {
          const bytes = await this.loadBytes(provider.hex_file)
          if (!bytes) continue
          const { Zip } = await import('./zip.js')
          const zip = await Zip.open(new Blob([bytes]))
          const entries = new Map()
          for (const name of zip.list().filter((n) => n.endsWith('.hex'))) for (const [code, rows] of parseHex(await zip.text(name))) entries.set(code, rows)
          this.files.push({ entries, overrides: readOverrides(provider.size_overrides) })
        } catch { /* a file that cannot be read is a file the font does not have */ }
      }
      this.done = true
    })()
    return this.loaded
  }

  /** The glyph for [character], or null. */
  get(character) {
    // Before the files are read nothing is known, and that must not be remembered.
    if (!this.done) return null
    const code = character.codePointAt(0)
    if (!this.cache.has(character)) {
      let found = null
      for (const { entries, overrides } of this.files) {
        const hex = entries.get(code)
        if (hex === undefined) continue
        const size = measure(hex, code, overrides)
        found = this.makeGlyph(pixels(hex, size.left, size.right), size.advance)
        break
      }
      this.cache.set(character, found)
    }
    return this.cache.get(character)
  }
}
