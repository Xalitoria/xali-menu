// Nexo, Oraxen, and ItemsAdder placeholder & tag expansion for Minecraft text previews.
// Handles:
//   - Pixel shifts:  <shift:N>, <s:N>, %nexo_shift_N%, %oraxen_shift_N%, %img_offset_N%
//   - Glyphs:        <glyph:name>, <g:name>, <image:name>, :name:, %img_name%, %nexo_name%, %oraxen_name%
//   - Glyph options: <glyph:name:colorable>, <glyph:name:shadow:#RRGGBBAA>, <glyph:name:#RRGGBB>
//   - Shadow tags:   <shadow:#RRGGBBAA>, <shadow:#RRGGBB>, <shadow:color>, </shadow>, <!shadow>
//   - PAPI bridge:   <papi:placeholder>, <placeholderapi:placeholder>

import { DEFAULT_FONT } from './fonts.js'

/**
 * Expands Nexo, Oraxen, and ItemsAdder tags/placeholders in [text] into canonical MiniMessage-like tags
 * understood by `mctext.js` (`<shift:N>`, `<font:key>...</font>`, `<color:...>`, `<shadow:...>`).
 *
 * @param {string} text
 * @param {Array<{name: string, char: string, font?: string|null, plugin?: string}>} [catalog]
 * @param {Map<string, string>|null} [fontIndex] optional char -> fontKey map from the loaded resource pack
 */
export function expandPlaceholders(text, catalog = [], fontIndex = null) {
  if (!text) return ''
  let result = String(text)

  // 1. PlaceholderAPI shift variants -> canonical `<shift:N>` (handled natively in mctext.js + text.js)
  result = result.replace(/%(?:nexo|oraxen)_shift_(-?\d+)%/gi, '<shift:$1>')
  result = result.replace(/%img_offset_(-?\d+)%/gi, '<shift:$1>')
  result = result.replace(/<s:(-?\d+)>/gi, '<shift:$1>')

  // 2. Nexo `<papi:placeholder>` / `<placeholderapi:placeholder>` -> `%placeholder%`
  result = result.replace(/<(?:papi|placeholderapi):([^<>]+)>/gi, (_, inner) => {
    const clean = inner.trim().replace(/^%|%$/g, '')
    return `%${clean}%`
  })

  // 3. Build fast lookup by glyph name (case-insensitive fallback as well)
  if (catalog && catalog.length > 0) {
    const byExact = new Map()
    const byLower = new Map()
    for (const entry of catalog) {
      if (!entry?.name || !entry?.char) continue
      byExact.set(entry.name, entry)
      const lower = entry.name.toLowerCase()
      if (!byLower.has(lower)) byLower.set(lower, entry)
    }
    const lookup = (name) => byExact.get(name) ?? byLower.get(name.toLowerCase()) ?? null
    const renderGlyph = (entry, modifiers = []) => {
      const font = entry.font || fontIndex?.get(entry.char) || null
      let colorable = false
      let explicitColor = null
      let shadowArg = null
      for (let i = 0; i < modifiers.length; i++) {
        const mod = modifiers[i].trim()
        const lower = mod.toLowerCase()
        if (lower === 'colorable' || lower === 'colored' || lower === 'tint') {
          colorable = true
        } else if (lower === 'shadow' && modifiers[i + 1] !== undefined) {
          shadowArg = modifiers[++i].trim()
        } else if (lower.startsWith('shadow=')) {
          shadowArg = mod.slice(7).trim()
        } else if (/^#[0-9a-f]{3,6}$/i.test(mod)) {
          explicitColor = mod
        }
      }
      let inner = font && font !== DEFAULT_FONT ? `<font:${font}>${entry.char}</font>` : entry.char
      if (shadowArg !== null) inner = `<shadow:${shadowArg}>${inner}</shadow>`
      if (explicitColor) inner = `<color:${explicitColor}>${inner}</color>`
      else if (!colorable) {
        // In Nexo, `<glyph:id>` without `:colorable` draws with white tint so GUI overlays stay true-color
        // even inside a dark-gray (`#404040`) container title!
        inner = `<white>${inner}</white>`
      }
      return inner
    }

    // 3a. `<glyph:name[:mod1[:mod2...]]>`, `<g:name[:...]>`, `<image:name[:...]>`
    result = result.replace(/<(?:glyph|g|image):([^<>]+)>/gi, (whole, body) => {
      const parts = body.split(':')
      // Try full name first (e.g. ItemsAdder `namespace:id`), then first segment with modifiers.
      const fullEntry = lookup(body.trim())
      if (fullEntry) return renderGlyph(fullEntry)
      const firstEntry = lookup(parts[0].trim())
      if (firstEntry) return renderGlyph(firstEntry, parts.slice(1))
      if (parts.length >= 2) {
        const nsEntry = lookup(`${parts[0].trim()}:${parts[1].trim()}`)
        if (nsEntry) return renderGlyph(nsEntry, parts.slice(2))
      }
      return whole
    })

    // 3b. `%nexo_<id>%`, `%oraxen_<id>%`, `%img_<id>%`
    result = result.replace(/%(?:nexo|oraxen|img)_([^%\s]+)%/gi, (whole, name) => {
      const entry = lookup(name)
      return entry ? renderGlyph(entry) : whole
    })

    // 3c. `:glyph_name:` shorthand (ItemsAdder / Oraxen / Nexo)
    result = result.replace(/:([a-zA-Z0-9_.-]+):/g, (whole, name) => {
      const entry = lookup(name)
      return entry ? renderGlyph(entry) : whole
    })
  }

  return result
}
