// Moving a picture glyph in a title: supports both raw space characters and Nexo `<shift:N>` tags.

import { shift, DEFAULT_FONT } from './fonts.js'

export function wrap(glyph, font) {
  return font && font !== DEFAULT_FONT ? `<font:${font}>${glyph}</font>` : glyph
}

/**
 * Reads the start of [text]: space characters or `<shift:N>` tags, the first picture glyph, and trailing shifts.
 */
export function parseHead(text, spaces, widthOf) {
  const characters = [...String(text ?? '')]
  let i = 0
  let offset = 0

  const readShiftTag = () => {
    const rest = characters.slice(i).join('')
    const match = rest.match(/^<(?:shift|s):(-?\d+)>/i)
    if (!match) return null
    i += [...match[0]].length
    return parseInt(match[1], 10)
  }

  while (i < characters.length) {
    if (spaces.has(characters[i])) { offset += spaces.get(characters[i++]); continue }
    const tagShift = readShiftTag()
    if (tagShift !== null) { offset += tagShift; continue }
    break
  }
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
  while (i < characters.length) {
    if (spaces.has(characters[i])) { net += spaces.get(characters[i++]); continue }
    const tagShift = readShiftTag()
    if (tagShift !== null) { net += tagShift; continue }
    break
  }
  return { glyph, font, offset, width, net, rest: characters.slice(i).join('') }
}

export function realign(head, offset, spaces) {
  const before = shift(offset, spaces)
  const after = shift(head.net - (offset + head.width), spaces)
  return { text: before.text + wrap(head.glyph, head.font) + after.text + head.rest, rest: before.rest + after.rest }
}

export function insert(glyph, font, width, offset, text, spaces) {
  return realign({ glyph, font, offset: 0, width, net: 0, rest: text }, offset, spaces)
}
