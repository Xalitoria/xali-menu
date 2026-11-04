// The fonts of a resource pack: which character is which picture, and how far each one moves the text on. The pack lists them as
// "providers" in assets/<namespace>/font/<name>.json; the game reads them in order and the first provider with a character wins.
//
// This file holds the parts that need no browser: reading the lists, following references between fonts, the widths of the
// space characters, and writing an offset as space characters. Turning pictures into glyphs is in pack.js.

export const DEFAULT_FONT = 'minecraft:default'

/** `font:name` and `name` both mean a key; a bare name is in the `minecraft` namespace. */
export function key(text, fallback = 'minecraft') {
  return text.includes(':') ? text : `${fallback}:${text}`
}

/**
 * The providers of every font in [zip], by key.
 * @param {{list(): string[], json(path: string): Promise<any>}} zip
 * @returns {Promise<Map<string, any[]>>}
 */
export async function readFonts(zip) {
  const fonts = new Map()
  for (const path of zip.list()) {
    const match = path.match(/^assets\/([^/]+)\/font\/(.+)\.json$/)
    if (!match) continue
    try {
      fonts.set(`${match[1]}:${match[2]}`, (await zip.json(path)).providers ?? [])
    } catch { /* a font that cannot be read is left out, as the game would */ }
  }
  return fonts
}

/**
 * The providers of [fontKey] with the `reference` providers replaced by the providers they point to, in order.
 * A font that refers to itself, directly or through others, is cut at the second visit.
 */
export function flatten(fonts, fontKey, seen = new Set()) {
  if (seen.has(fontKey)) return []
  seen.add(fontKey)
  const result = []
  for (const provider of fonts.get(fontKey) ?? []) {
    if (provider.type === 'reference') result.push(...flatten(fonts, key(provider.id), seen))
    else result.push({ ...provider, font: fontKey })
  }
  return result
}

/** The characters of a `chars` entry: a string is one row, and each code point is one cell. */
export function cells(row) {
  return [...row]
}

/** The advance of each character of the `space` providers, in pixels. @returns {Map<string, number>} */
export function spaces(providers) {
  const result = new Map()
  for (const provider of providers) {
    if (provider.type !== 'space') continue
    for (const [character, advance] of Object.entries(provider.advances ?? {})) if (!result.has(character)) result.set(character, advance)
  }
  return result
}

/**
 * The characters that move the text on by [pixels] (negative to move it back), from the space characters a font has. It uses
 * the biggest steps first, so a shift of -37 with steps of 1, 2, 4, 8, 16 and 32 is -32 -4 -1.
 * @param {number} pixels a whole number
 * @param {Map<string, number>} advances
 * @returns {{text: string, rest: number}} the characters, and what could not be made up (0 when it is exact)
 */
export function shift(pixels, advances) {
  const steps = [...advances.entries()].filter(([, advance]) => Number.isInteger(advance) && advance !== 0).sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]))
  let rest = Math.trunc(pixels)
  let text = ''
  for (const [character, advance] of steps) {
    if (Math.sign(advance) !== Math.sign(rest)) continue
    while (Math.abs(rest) >= Math.abs(advance)) { text += character; rest -= advance }
  }
  return { text, rest }
}

/**
 * A glyph placed at [offset] pixels, written as text: shift, the glyph, then the shift that puts the text after it back
 * where it was, so what follows is not moved. [width] is the advance of the glyph.
 */
export function placed(glyphChar, offset, width, advances) {
  const before = shift(offset, advances)
  const after = shift(-(offset + width), advances)
  return { text: before.text + glyphChar + after.text, rest: before.rest + after.rest }
}
