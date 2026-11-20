// Pure Bedrock device profiles, presentation planning, auto-form button conversion and compatibility analysis for the editor.
// Mirrors the rules in `BedrockPlanner.kt` so the editor preview matches what Bedrock players see in game.

import { bySlot } from './inventory.js'
import { listedSlots } from './pages.js'

/** Simulated client devices available in the editor's "View as" selector. */
export const DEVICES = [
  { id: 'java', label: 'Java (PC)', edition: 'JAVA', device: 'PC', os: 'JAVA', input: 'KEYBOARD_MOUSE', ui: 'JAVA' },
  { id: 'bedrock-pc', label: 'Bedrock (PC)', edition: 'BEDROCK', device: 'PC', os: 'WINDOWS', input: 'KEYBOARD_MOUSE', ui: 'CLASSIC' },
  { id: 'bedrock-mobile', label: 'Bedrock (Mobile)', edition: 'BEDROCK', device: 'MOBILE', os: 'ANDROID', input: 'TOUCH', ui: 'POCKET' },
  { id: 'bedrock-console', label: 'Bedrock (Console)', edition: 'BEDROCK', device: 'CONSOLE', os: 'XBOX', input: 'CONTROLLER', ui: 'CLASSIC' },
]

export function deviceById(id) {
  return DEVICES.find((d) => d.id === id) ?? DEVICES[0]
}

/** Whether [device] matches the `devices`, `inputs` and `ui` filters of a menu's `bedrock` block. */
export function matchesBedrockFilter(device, cfg) {
  if (!cfg || typeof cfg !== 'object') return false
  const list = (v) => (Array.isArray(v) ? v.map((x) => String(x).toUpperCase()) : v ? [String(v).toUpperCase()] : [])
  const devices = list(cfg.devices)
  const inputs = list(cfg.inputs)
  const ui = list(cfg.ui)
  if (devices.length && !devices.includes(device.device)) return false
  if (inputs.length && !inputs.includes(device.input)) return false
  if (ui.length && !ui.includes(device.ui)) return false
  return true
}

/**
 * Decides how [menu] will be presented on [device] (`inventory`, `redirect`, `simple`, `modal`, or `custom`).
 * [defaultMode] mirrors `bedrock.default-mode` in `config.yml` (`inventory`, `touch-form`, `always-form`).
 */
export function presentationFor(menu, device, defaultMode = 'inventory') {
  if (!device || device.edition !== 'BEDROCK') return { kind: 'inventory' }
  const cfg = menu?.bedrock
  const prefersForm = device.input === 'TOUCH' || device.input === 'CONTROLLER' || device.ui === 'POCKET'
  const fallback = () => {
    if (defaultMode === 'always-form') return { kind: 'simple' }
    if (defaultMode === 'touch-form' && prefersForm) return { kind: 'simple' }
    return { kind: 'inventory' }
  }
  if (cfg && typeof cfg === 'object') {
    const filtered = Boolean((cfg.devices?.length) || (cfg.inputs?.length) || (cfg.ui?.length))
    if (!matchesBedrockFilter(device, cfg)) return filtered ? { kind: 'inventory' } : fallback()
    if (typeof cfg.menu === 'string' && cfg.menu.trim()) return { kind: 'redirect', menu: cfg.menu.trim() }
    const mode = String(cfg.mode ?? 'AUTO').toUpperCase()
    if (mode === 'INVENTORY') return { kind: 'inventory' }
    if (mode === 'SIMPLE') return { kind: 'simple' }
    if (mode === 'MODAL') return { kind: 'modal' }
    if (mode === 'CUSTOM') return { kind: 'custom' }
    if (Array.isArray(cfg.fields) && cfg.fields.length) return { kind: 'custom' }
    if (cfg.confirm || cfg.cancel) return { kind: 'modal' }
    if (Array.isArray(cfg.buttons) && cfg.buttons.length) return { kind: 'simple' }
    return fallback()
  }
  return fallback()
}

/** Removes Unicode Private Use Area characters (`\uE000-\uF8FF`) used by Java resource-pack glyphs/shifts. */
export function stripPrivateUse(text) {
  return String(text ?? '').replace(/[\uE000-\uF8FF]/g, '').trim()
}

/** Whether [text] contains Java-only custom glyph tags or Private Use Area characters. */
export function hasJavaGlyphs(text) {
  const s = String(text ?? '')
  return /[\uE000-\uF8FF]/.test(s) || /<(glyph|shift|font)[^>]*>/i.test(s)
}

/** Title shown to [device] for [menu]. */
export function titleForDevice(menu, device) {
  if (device?.edition === 'BEDROCK') {
    if (menu?.bedrock?.title) return menu.bedrock.title
    return stripPrivateUse(menu?.title ?? '')
  }
  return menu?.title ?? ''
}

/** Click choices of [item], mirroring `ClickChoices.choicesFor`. */
export function clickChoices(item) {
  const c = item?.click ?? {}
  const b = item?.bedrock ?? {}
  const specific = []
  if (c.left) specific.push({ kind: 'left', label: b['left-label'] || 'Left click', handler: c.left })
  if (c.right) specific.push({ kind: 'right', label: b['right-label'] || 'Right click', handler: c.right })
  if (c['shift-left']) specific.push({ kind: 'shift-left', label: b['shift-left-label'] || 'Shift + Left click', handler: c['shift-left'] })
  if (c['shift-right']) specific.push({ kind: 'shift-right', label: b['shift-right-label'] || 'Shift + Right click', handler: c['shift-right'] })
  if (c.middle) specific.push({ kind: 'middle', label: b['middle-label'] || 'Middle click', handler: c.middle })
  if (specific.length) return specific
  return c.any ? [{ kind: 'any', label: 'Select', handler: c.any }] : []
}

/** Whether [item] is treated as a decorative filler in auto-converted Bedrock forms. */
export function isFiller(item) {
  if (!item) return true
  if (item.bedrock?.hidden) return true
  if (clickChoices(item).length > 0) return false
  const name = String(item.name ?? '').trim()
  const lore = Array.isArray(item.lore) ? item.lore : []
  return !name && lore.length === 0 && !item.bedrock?.text
}

/** Turns `diamond_sword` into `Diamond Sword`. */
export function humanMaterial(spec) {
  const base = String(spec ?? '').split(':').pop().trim()
  if (!base) return 'Item'
  return base.split('_').filter(Boolean).map((p) => p[0].toUpperCase() + p.slice(1)).join(' ')
}

/**
 * Buttons rendered by a Bedrock `SimpleForm` for [menu] (using `shownMenu` when paged so example/live elements appear).
 * Returns `{ explicit: boolean, buttons: Array<{ id: string|null, label: string, sub: string, item: object|null, image: string|null, choices: Array, paging?: number }> }`.
 */
export function simpleFormButtons(menu, shownMenu = menu, { page = 1, pages = 1 } = {}) {
  const cfg = menu?.bedrock ?? {}
  if (Array.isArray(cfg.buttons) && cfg.buttons.length) {
    return {
      explicit: true,
      buttons: cfg.buttons.map((b, i) => {
        const lines = String(b.text ?? '').split('\n')
        return { id: null, buttonIndex: i, label: lines[0] ?? '', sub: lines.slice(1).join('\n'), item: null, image: b.image ?? null, choices: [] }
      }),
    }
  }
  const skipFillers = cfg['skip-fillers'] !== false
  const includeLore = cfg['include-lore'] !== false
  const slots = bySlot(shownMenu)
  const pagedSet = new Set(listedSlots(menu?.pages))
  const ordered = [...slots.keys()].sort((a, b) => a - b)
  const seen = new Set()
  const buttons = []

  for (const slot of ordered) {
    const names = slots.get(slot) ?? []
    const id = names[0]
    if (!id) continue
    const item = shownMenu.items?.[id]
    if (!item) continue
    const isElement = id.startsWith('@element') || id === '@empty'
    if (!isElement && pagedSet.has(slot)) continue
    if (!isElement) {
      if (seen.has(id)) continue
      seen.add(id)
    }
    if (item.bedrock?.hidden) continue
    if (skipFillers && isFiller(item)) continue
    const override = item.bedrock?.text
    let label = ''
    let sub = ''
    if (override) {
      const parts = String(override).split('\n')
      label = parts[0] ?? ''
      sub = parts.slice(1).join('\n')
    } else {
      label = item.name?.trim() ? item.name : humanMaterial(item.material)
      const firstLore = (Array.isArray(item.lore) ? item.lore : []).find((l) => String(l ?? '').trim())
      if (includeLore && firstLore) sub = String(firstLore)
    }
    buttons.push({
      id: isElement ? null : id,
      paged: isElement,
      label,
      sub,
      item,
      image: item.bedrock?.image ?? null,
      choices: clickChoices(item),
    })
  }

  if (menu?.pages && pages > 1) {
    if (page > 1) buttons.push({ id: null, label: cfg['prev-button'] ?? `&e« Previous (${page - 1}/${pages})`, sub: '', item: { material: 'arrow' }, image: null, choices: [], paging: -1 })
    if (page < pages) buttons.push({ id: null, label: cfg['next-button'] ?? `&eNext (${page + 1}/${pages}) »`, sub: '', item: { material: 'arrow' }, image: null, choices: [], paging: 1 })
  }
  return { explicit: false, buttons }
}

/**
 * Compatibility notes for [menu] when viewed on [device] (Java returns none).
 * Each note has `{ level: 'info' | 'warn', message: string }`.
 */
export function compatibilityNotes(menu, device, defaultMode = 'inventory') {
  if (!device || device.edition !== 'BEDROCK') return []
  const notes = []
  const cfg = menu?.bedrock ?? {}
  const pres = presentationFor(menu, device, defaultMode)

  if (hasJavaGlyphs(menu?.title) && !cfg.title) {
    notes.push({ level: 'warn', message: 'Title uses Java font/glyph tags; set bedrock.title so Bedrock players see clean text.' })
  }

  const items = Object.entries(menu?.items ?? {})
  const invItems = items.filter(([, it]) => Array.isArray(it['inventory-slots']) && it['inventory-slots'].length > 0)
  if (invItems.length > 0 && device.ui === 'POCKET' && !cfg['inventory-overlay']) {
    notes.push({ level: 'info', message: `${invItems.length} item(s) use inventory-slots, which are skipped on Pocket UI unless bedrock.inventory-overlay is on.` })
  }

  const acceptors = items.filter(([, it]) => it.accept)
  if (acceptors.length > 0 && pres.kind !== 'inventory') {
    notes.push({ level: 'warn', message: `${acceptors.length} item(s) accept deposited items, which only works in INVENTORY mode.` })
  }

  const multiClick = items.filter(([, it]) => clickChoices(it).length > 1)
  if (multiClick.length > 0 && (device.input === 'TOUCH' || device.input === 'CONTROLLER' || pres.kind === 'simple')) {
    const unlabeled = multiClick.filter(([, it]) => !it.bedrock?.['left-label'] && !it.bedrock?.['right-label'])
    if (cfg['action-picker'] === false) {
      notes.push({ level: 'warn', message: `${multiClick.length} item(s) have multiple click handlers, but bedrock.action-picker is off so only the first can run on ${device.label}.` })
    } else if (unlabeled.length > 0) {
      notes.push({ level: 'info', message: `${unlabeled.length} multi-click item(s) will open a Bedrock choice form; set bedrock.left-label / right-label for clearer button names.` })
    }
  }

  return notes
}
