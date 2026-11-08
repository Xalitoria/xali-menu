// Shows text the way a menu writes it as colored runs, so the editor can preview names, titles and lore. It reads the formats of the color
// module (`&c`, `&#rgb`, `&#rrggbb`, `&x&r&r&g&g&b&b`, `§` for `&`, `{#name}`, `{#rrggbb}`, the gradients `{#a>}text{#b<}` with `{#c<>}` between two)
// and the common MiniMessage tags (`<red>`, `<#rrggbb>`, `<color:...>`, `<gradient:...>`, `<rainbow>`, the decorations, `<font:...>`, `<reset>`).
// Anything else is shown as typed.
//
// Gradients are mixed in plain RGB here. The plugin mixes with Mixbox (pigments), so the colors in between differ a little; the ends are the same.

import { NAMED_COLORS } from './colors.js'

const COLORS = {
  0: '#000000', 1: '#0000aa', 2: '#00aa00', 3: '#00aaaa', 4: '#aa0000', 5: '#aa00aa', 6: '#ffaa00', 7: '#aaaaaa',
  8: '#555555', 9: '#5555ff', a: '#55ff55', b: '#55ffff', c: '#ff5555', d: '#ff55ff', e: '#ffff55', f: '#ffffff',
}
/** The names MiniMessage knows for the sixteen colors, with the aliases the color module adds. */
const NAMED = {
  black: '0', dark_blue: '1', dark_green: '2', dark_aqua: '3', dark_red: '4', dark_purple: '5', gold: '6', gray: '7', grey: '7',
  dark_gray: '8', dark_grey: '8', blue: '9', green: 'a', aqua: 'b', red: 'c', light_purple: 'd', yellow: 'e', white: 'f',
}
const STYLES = { l: 'bold', o: 'italic', n: 'underline', m: 'strike', k: 'obfuscated', b: 'bold', i: 'italic', em: 'italic', u: 'underline', st: 'strike', obf: 'obfuscated',
  bold: 'bold', italic: 'italic', underlined: 'underline', strikethrough: 'strike', obfuscated: 'obfuscated' }
const DECOR = /[k-o]/i
const TAG = /^<[/!]?[a-z#][^<>]*>/i

// ---- colors

/** `#rrggbb` for the text of a color: hex (3 or 6 digits, with or without `#`), a name of the color module, or a name of a legacy color. Null when it is none. */
export function colorOf(query) {
  const text = String(query).trim()
  const hex = text.match(/^#?((?:[0-9a-f]{3}){1,2})$/i)
  if (hex) {
    const digits = hex[1].length === 3 ? [...hex[1]].map((c) => c + c).join('') : hex[1]
    return `#${digits.toLowerCase()}`
  }
  return NAMED_COLORS.get(text.replace(/_/g, '').toLowerCase()) ?? null
}

const channels = (color) => { const n = parseInt(color.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255] }
const toHex = (rgb) => `#${rgb.map((v) => Math.round(Math.min(255, Math.max(0, v))).toString(16).padStart(2, '0')).join('')}`

/** The color [t] (0 to 1) of the way along [stops] (`#rrggbb` list), mixed in RGB. */
export function mix(stops, t) {
  if (stops.length === 1) return stops[0]
  const scaled = Math.min(Math.max(t, 0), 1) * (stops.length - 1)
  const i = Math.min(Math.floor(scaled), stops.length - 2)
  const [a, b] = [channels(stops[i]), channels(stops[i + 1])]
  const f = scaled - i
  return toHex(a.map((v, k) => v + (b[k] - v) * f))
}

function hsv(h, s, v) {
  const f = (n) => { const k = (n + h * 6) % 6; return v - v * s * Math.max(0, Math.min(k, 4 - k, 1)) }
  return toHex([f(5) * 255, f(3) * 255, f(1) * 255])
}

// ---- gradients, expanded to one color for each character, before the text is read

/**
 * [text] with a color in front of each character that shows: tags stay uncolored and uncounted, decoration codes stay in force, and `\<` is one character.
 * [colorAt] gets the place of the character, 0 to 1, as the plugin counts it. Colors are written as `&#rrggbb`, or `<#rrggbb>` in [minimessage] mode.
 */
function colorize(text, colorAt, minimessage = false) {
  const chars = []
  let decor = ''
  let i = 0
  // First pass: what is a character that shows, and what is a tag or a code.
  while (i < text.length) {
    const c = text[i]
    if ((c === '&' || c === '§') && /[k-or]/i.test(text[i + 1] ?? '')) {
      chars.push({ raw: text.slice(i, i + 2), decor: true }); i += 2; continue
    }
    if (c === '<') { const tag = text.slice(i).match(TAG); if (tag) { chars.push({ raw: tag[0], tag: true }); i += tag[0].length; continue } }
    if (c === '\\' && text[i + 1] === '<') { chars.push({ raw: '\\<', shown: true }); i += 2; continue }
    chars.push({ raw: c, shown: true }); i++
  }
  const total = chars.filter((x) => x.shown).length
  const last = Math.max(total - 1, 1)
  let n = 0
  let out = ''
  for (const part of chars) {
    if (part.decor) { decor = /[rR]$/.test(part.raw) ? '' : decor + part.raw; out += part.raw; continue }
    if (part.tag) { out += part.raw; continue }
    const color = colorAt(total === 1 ? 0 : n / last)
    out += (minimessage ? `<${color}>` : `&${color}`) + decor + part.raw
    n++
  }
  return out
}

/** `{#a>}text{#b<}` (and `{#c<>}` between two) as one color for each character. */
function legacyGradients(text) {
  const separated = text.replace(/\{#([0-9a-z]{3,}?)<>}/gi, (whole, name) => { const c = colorOf(name); return c ? `{${c}<}{${c}>}` : whole })
  return separated.replace(/\{#([0-9a-z]{3,}?)>}([\s\S]*?)\{#([0-9a-z]{3,}?)<}/gi, (whole, from, inner, to) => {
    const [a, b] = [colorOf(from), colorOf(to)]
    if (!a || !b) return whole
    if (inner === '') return `&${b}`
    if (a === b) return `&${a}${inner}`
    return colorize(inner, (t) => mix([a, b], t))
  })
}

/** The color a MiniMessage tag names: a legacy color's name, hex, or a name of the color module. */
const miniColor = (text) => (COLORS[NAMED[text.toLowerCase()]] ?? colorOf(text))

/** `<gradient:a:b:c>text</gradient>` and `<rainbow>text</rainbow>` as one color for each character; unclosed, they go to the end. */
function miniGradients(text) {
  return text.replace(/<(gradient|rainbow)((?::[^<>:]*)*)>([\s\S]*?)(?:<\/\1>|$)/gi, (whole, kind, args, inner) => {
    const parts = args.split(':').slice(1)
    if (kind.toLowerCase() === 'rainbow') {
      const reverse = parts.some((p) => p.startsWith('!'))
      const phase = Number(parts.find((p) => /^!?-?\d*\.?\d+$/.test(p))?.replace('!', '')) || 0
      return colorize(inner, (t) => hsv((((reverse ? 1 - t : t) + phase / 360) % 1 + 1) % 1, 1, 1), true) + '</color>'
    }
    const numeric = parts.length > 0 && /^-?\d*\.?\d+$/.test(parts[parts.length - 1])
    const phase = numeric ? Number(parts.pop()) : 0
    const stops = parts.map(miniColor).filter(Boolean)
    const use = stops.length >= 2 ? stops : ['#ffffff', '#000000']
    return colorize(inner, (t) => mix(use, phase ? (((t + phase) % 1) + 1) % 1 : t), true) + '</color>'
  })
}

// ---- runs

/**
 * Splits [text] into runs with a style: `[{ text, color, bold, italic, underline, strike, obfuscated, font }]`.
 * @param {string} text
 */
export function runs(text) {
  const source = miniGradients(legacyGradients(text))
  const out = []
  let style = {}
  let buffer = ''
  const flush = () => { if (buffer) { out.push({ text: buffer, ...style }); buffer = '' } }
  const set = (change) => { flush(); style = { ...style, ...change } }
  /** A legacy color ends the decorations before it, as in the game's chat; the font stays. */
  const legacy = (color) => { flush(); style = { color, ...(style.font ? { font: style.font } : {}) } }
  for (let i = 0; i < source.length; i++) {
    const c = source[i]
    const next = source[i + 1]
    if ((c === '&' || c === '§') && next !== undefined) {
      const hex = source.slice(i + 1).match(/^#((?:[0-9a-f]{3}){1,2})/i)
      if (hex) { legacy(colorOf(hex[1])); i += hex[0].length; continue }
      const bukkit = source.slice(i).match(/^[&§]x((?:[&§][0-9a-f]){6}|(?:[&§][0-9a-f]){3})/i)
      if (bukkit) { legacy(colorOf(bukkit[1].replace(/[&§]/g, ''))); i += bukkit[0].length - 1; continue }
      const code = next.toLowerCase()
      if (code in COLORS) { legacy(COLORS[code]); i++; continue }
      if (code === 'r') { flush(); style = {}; i++; continue }
      if (code in STYLES && DECOR.test(code)) { set({ [STYLES[code]]: true }); i++; continue }
    }
    if (c === '{' && next === '#') {
      const named = source.slice(i).match(/^\{#([0-9a-z]{3,}?)}/i)
      const color = named && colorOf(named[1])
      if (color) { legacy(color); i += named[0].length - 1; continue }
    }
    if (c === '<') {
      const end = source.indexOf('>', i)
      const tag = end > 0 ? source.slice(i + 1, end) : null
      if (tag !== null && /^[/!]?[a-z#][^<>]*$/i.test(tag)) {
        const closing = tag.startsWith('/')
        const negated = tag.startsWith('!')
        const body = tag.replace(/^[/!]/, '')
        const [head, ...args] = body.split(':')
        const name = head.toLowerCase()
        const hexColor = body.match(/^#[0-9a-f]{6}$/i)
        if (hexColor) { set({ color: closing ? undefined : body.toLowerCase() }); i = end; continue }
        if (name in NAMED) { set({ color: closing ? undefined : COLORS[NAMED[name]] }); i = end; continue }
        if (name === 'color' || name === 'colour' || name === 'c') {
          const color = args[0] && miniColor(args[0])
          set({ color: closing ? undefined : color ?? undefined }); i = end; continue
        }
        if (name in STYLES && !/^[k-o]$/.test(name)) { set({ [STYLES[name]]: !closing && !negated }); i = end; continue }
        if (name === 'font') { set({ font: closing ? undefined : args.join(':') || undefined }); i = end; continue }
        if (name === 'reset') { flush(); style = {}; i = end; continue }
        if (closing || negated) { i = end; continue }
      }
    }
    if (c === '\\' && next === '<') { buffer += '<'; i++; continue }
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
