// Shows text the way a menu writes it (`&6gold`, `&#ff8800hex`, `<red>`, `<gradient:...>`) as colored spans, so the editor can
// preview names and titles. It covers the legacy codes, hex colors and the common MiniMessage tags; anything else is shown as typed.

const COLORS = {
  0: '#000000', 1: '#0000aa', 2: '#00aa00', 3: '#00aaaa', 4: '#aa0000', 5: '#aa00aa', 6: '#ffaa00', 7: '#aaaaaa',
  8: '#555555', 9: '#5555ff', a: '#55ff55', b: '#55ffff', c: '#ff5555', d: '#ff55ff', e: '#ffff55', f: '#ffffff',
}
const NAMED = {
  black: '0', dark_blue: '1', dark_green: '2', dark_aqua: '3', dark_red: '4', dark_purple: '5', gold: '6', gray: '7',
  dark_gray: '8', blue: '9', green: 'a', aqua: 'b', red: 'c', light_purple: 'd', yellow: 'e', white: 'f',
}
const STYLES = { l: 'bold', o: 'italic', n: 'underline', m: 'strike', k: 'obfuscated', bold: 'bold', italic: 'italic', underlined: 'underline', strikethrough: 'strike', obfuscated: 'obfuscated' }

/**
 * Splits [text] into runs with a style: `[{ text, color, bold, italic, underline, strike }]`.
 * @param {string} text
 */
export function runs(text) {
  const out = []
  let style = {}
  let buffer = ''
  const flush = () => { if (buffer) { out.push({ text: buffer, ...style }); buffer = '' } }
  const set = (change) => { flush(); style = { ...style, ...change } }
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    const next = text[i + 1]
    if ((c === '&' || c === '§') && next !== undefined) {
      const hex = text.slice(i + 1, i + 8).match(/^#[0-9a-fA-F]{6}/)
      if (hex) { set({ color: hex[0] }); i += 7; continue }
      const code = next.toLowerCase()
      if (code in COLORS) { flush(); style = { color: COLORS[code] }; i++; continue }
      if (code === 'r') { flush(); style = {}; i++; continue }
      if (code in STYLES) { set({ [STYLES[code]]: true }); i++; continue }
    }
    if (c === '<') {
      const end = text.indexOf('>', i)
      const tag = end > 0 ? text.slice(i + 1, end) : null
      if (tag !== null && /^\/?[a-z_#:0-9,.\/ -]+$/i.test(tag)) {
        const name = tag.replace(/^\//, '').split(':')[0].toLowerCase()
        const closing = tag.startsWith('/')
        const hexColor = tag.match(/^#[0-9a-fA-F]{6}$/)
        if (hexColor) { set({ color: tag }); i = end; continue }
        if (name in NAMED) { closing ? set({ color: undefined }) : set({ color: COLORS[NAMED[name]] }); i = end; continue }
        if (name in STYLES) { set({ [STYLES[name]]: !closing }); i = end; continue }
        if (name === 'font') { set({ font: closing ? undefined : tag.slice(5) }); i = end; continue }
        if (name === 'reset') { flush(); style = {}; i = end; continue }
        if (name === 'gradient' || name === 'rainbow') { const first = tag.split(':')[1]; set({ color: first && /^#/.test(first) ? first : '#ff55ff' }); i = end; continue }
        if (closing) { i = end; continue }
      }
    }
    buffer += c
  }
  flush()
  return out
}

/** The runs as DOM, in the font of the game when the page has it. */
export function render(text) {
  const root = document.createElement('span')
  root.className = 'mc'
  for (const run of runs(text)) {
    const span = document.createElement('span')
    span.textContent = run.text
    if (run.color) span.style.color = run.color
    if (run.bold) span.style.fontWeight = 'bold'
    if (run.italic) span.style.fontStyle = 'italic'
    const lines = [run.underline && 'underline', run.strike && 'line-through'].filter(Boolean)
    if (lines.length) span.style.textDecoration = lines.join(' ')
    root.append(span)
  }
  return root
}

/** The text without any code, for a width or a label. */
export function plain(text) {
  return runs(text).map((r) => r.text).join('')
}
