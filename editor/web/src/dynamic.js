// A number or a switch in a menu file may be written as text with placeholders (`amount = "%vault_eco_balance%"`) that the plugin works out when it
// draws the item. The editor cannot work them out, so it shows such an item with the fallback, and a value that is plain text of a number is a number.

/** The number [value] stands for: a number, or text that is one; [fallback] for text with placeholders or nothing. */
export function numberOf(value, fallback) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '' && Number.isFinite(Number(value))) return Number(value)
  return fallback
}

/** The switch [value] stands for: `true` or `false`, or text that says so; [fallback] for text with placeholders or nothing. */
export function flagOf(value, fallback) {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    const text = value.trim().toLowerCase()
    if (['true', 'yes', 'on', '1'].includes(text)) return true
    if (['false', 'no', 'off', '0'].includes(text)) return false
  }
  return fallback
}

/** Whether [value] is text that is not yet a number or a switch: it holds placeholders the plugin works out. */
export const isDynamic = (value, kind) => typeof value === 'string' && (kind === 'boolean' ? flagOf(value, null) === null : numberOf(value, null) === null)

/** What a box of a dynamic value holds as it is typed: a number or a switch when the text is one, otherwise the text itself. */
export function parseDynamic(text, kind) {
  const trimmed = text.trim()
  if (kind === 'boolean') return flagOf(trimmed, null) ?? text
  return trimmed !== '' && Number.isInteger(Number(trimmed)) ? Number(trimmed) : text
}
