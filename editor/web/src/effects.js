// The words for the numbers of a potion effect and an attribute: a menu file writes ticks and a level minus one, a person thinks in seconds and levels.

const ROMAN = [[1000, 'M'], [900, 'CM'], [500, 'D'], [400, 'CD'], [100, 'C'], [90, 'XC'], [50, 'L'], [40, 'XL'], [10, 'X'], [9, 'IX'], [5, 'V'], [4, 'IV'], [1, 'I']]

/** A level as the game writes it up to ten (`III`), and as a number past it, as the game does. */
export function roman(level) {
  const n = Math.floor(Number(level))
  if (!Number.isFinite(n) || n < 1) return String(level)
  if (n > 10) return String(n)
  let rest = n
  let out = ''
  for (const [value, letters] of ROMAN) while (rest >= value) { out += letters; rest -= value }
  return out
}

/** The amplifier of the file (0 is level I) and the level a person says (1 is level I). */
export const levelOf = (amplifier) => (Number(amplifier) || 0) + 1
export const amplifierOf = (level) => Math.max(0, Math.round(Number(level)) - 1)

/** Ticks as seconds (20 ticks are one second), and back; a second of 0.5 is 10 ticks. */
export const secondsOf = (ticks) => Math.round((Number(ticks) || 0) * 5) / 100
export const ticksOf = (seconds) => Math.max(0, Math.round(Number(seconds) * 20))

/** How long [ticks] last, as the game's tooltip shows an effect: `1:30`, or `∞` for the game's infinite one (-1). */
export function clock(ticks) {
  const t = Number(ticks)
  if (t < 0) return '∞'
  const total = Math.floor(t / 20)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

/** The operations of an attribute modifier, as the values of the model's enum with what each does. */
export const OPERATIONS = [
  ['ADD', 'Add', 'adds the amount to the attribute'],
  ['MULTIPLY_BASE', 'Add a share of the base', 'adds the amount times the base value: 0.5 is 50% more of it'],
  ['MULTIPLY_TOTAL', 'Multiply the total', 'multiplies the result of the others by 1 plus the amount'],
]

/** What an amount does to an attribute with [operation], in a few words: `+5`, `+50% of the base`, `×1.5`. */
export function effectOf(operation, amount) {
  const n = Number(amount)
  if (!Number.isFinite(n)) return ''
  const sign = n >= 0 ? '+' : '−'
  const abs = Math.abs(n)
  if (operation === 'MULTIPLY_BASE') return `${sign}${Math.round(abs * 10000) / 100}% of the base`
  if (operation === 'MULTIPLY_TOTAL') return `×${Math.round((1 + n) * 10000) / 10000}`
  return `${sign}${abs}`
}
