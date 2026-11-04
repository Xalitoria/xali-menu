// Moving a picture glyph in a title. A menu title is text: to draw a window background glyph at the right place, the title
// starts with space characters that push the cursor, then the glyph, then space characters that pull the cursor back. This
// finds that structure in a title and writes it again with another offset, leaving the text after it where it was.
//
// A glyph that is not in the default font (most plugin glyphs are in their own, such as `nexo:default`) is wrapped in
// `<font:key>...</font>`. The space characters stay outside it, in the default font, where the shift characters are.

import { shift, DEFAULT_FONT } from './fonts.js'

/** The glyph as it is written in a title: plain in the default font, otherwise in a font tag. */
export function wrap(glyph, font) {
  return font && font !== DEFAULT_FONT ? `<font:${font}>${glyph}</font>` : glyph
}

/**
 * Reads the start of [text]: the space characters, the first picture glyph, the space characters after it.
 * @param {string} text
 * @param {Map<string, number>} spaces advance of each space character of the default font
 * @param {(character: string, font: string|null) => number|null} widthOf the advance of a picture glyph (in [font], null for the default), null for a character that is not one
 * @returns {{glyph: string, font: string|null, offset: number, width: number, net: number, rest: string}|null}
 *   [offset] is the push before the glyph, [net] how far the cursor has moved when the rest starts (the glyph's width included)
 */
export function parseHead(text, spaces, widthOf) {
  const characters = [...text]
  let i = 0
  let offset = 0
  while (i < characters.length && spaces.has(characters[i])) offset += spaces.get(characters[i++])
  if (i >= characters.length) return null
  let font = null
  if (characters.slice(i, i + 6).join('') === '<font:') {
    const close = characters.indexOf('>', i)
    if (close < 0) return null
    font = characters.slice(i + 6, close).join('')
    i = close + 1
  }
  const glyph = characters[i]
  const width = glyph === undefined ? null : widthOf(glyph, font)
  if (width === null || width === undefined) return null
  i++
  if (font !== null) {
    if (characters.slice(i, i + 7).join('') !== '</font>') return null
    i += 7
  }
  let net = offset + width
  while (i < characters.length && spaces.has(characters[i])) net += spaces.get(characters[i++])
  return { glyph, font, offset, width, net, rest: characters.slice(i).join('') }
}

/**
 * The title for [head] moved to [offset]. The text after it keeps the place it had. [rest] in the answer is what the space
 * characters could not make up, in pixels (0 when they could).
 */
export function realign(head, offset, spaces) {
  const before = shift(offset, spaces)
  const after = shift(head.net - (offset + head.width), spaces)
  return { text: before.text + wrap(head.glyph, head.font) + after.text + head.rest, rest: before.rest + after.rest }
}

/** A glyph put in front of [text] at [offset], with the text left at the start of the line, where it would have been. */
export function insert(glyph, font, width, offset, text, spaces) {
  return realign({ glyph, font, offset: 0, width, net: 0, rest: text }, offset, spaces)
}
