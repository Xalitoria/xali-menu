// Finding the word a mistyped one was meant to be: the same rules as `Suggest` in the plugin, so the editor and the plugin give the same advice.

/** The number of single-letter changes that turn [a] into [b]. */
export function distance(a, b) {
  let previous = Array.from({ length: b.length + 1 }, (_, i) => i)
  for (let i = 1; i <= a.length; i++) {
    const row = [i]
    for (let j = 1; j <= b.length; j++) row[j] = Math.min(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1))
    previous = row
  }
  return previous[b.length]
}

const plain = (text) => text.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')

/** The candidate that [word] is probably a misspelling of, or null: the same letters in another style (`max_stack_size`), else the closest within a few edits. */
export function closest(word, candidates) {
  const style = candidates.find((c) => c !== word && plain(c) === plain(word))
  if (style) return style
  const limit = word.length <= 4 ? 1 : 2
  let best = null
  for (const candidate of candidates) {
    const d = distance(word.toLowerCase(), candidate.toLowerCase())
    if (d >= 1 && d <= limit && (best === null || d < best.d)) best = { candidate, d }
  }
  return best?.candidate ?? null
}

/** ` (did you mean 'slot'?)`, or nothing. */
export function hint(word, candidates) {
  const found = closest(word, candidates)
  return found ? ` (did you mean '${found}'?)` : ''
}
