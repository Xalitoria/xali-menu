// The list of items a menu can use, for the search of the material field: every item of the game, and the custom items the server's plugins
// list. From a server both come from it; otherwise the game's items are read from the public copy of the assets (the files of
// assets/minecraft/items, and their names from the language file) and custom items are typed by hand.

import { settings as assetSettings } from './assets.js'

/** @typedef {{id: string, name: string, kind: 'vanilla'|'custom', source: string|null}} Entry */

/**
 * The entries of [vanilla] ids and the [custom] ids by source prefix, with names from [lang] where there are any.
 * @param {string[]} vanilla
 * @param {Record<string, string[]>} custom
 * @param {Record<string, string>} [lang]
 * @returns {Entry[]}
 */
export function entries(vanilla, custom = {}, lang = {}) {
  const named = (id) => lang[`item.minecraft.${id}`] ?? lang[`block.minecraft.${id}`] ?? id.replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
  const out = vanilla.map((id) => ({ id, name: named(id), kind: 'vanilla', source: null }))
  for (const [prefix, ids] of Object.entries(custom)) for (const id of ids) out.push({ id: `${prefix}:${id}`, name: id, kind: 'custom', source: prefix })
  return out
}

/**
 * The entries that match [query], best first: every word of the query has to be in the id or the name; an id that is the query comes
 * first, then ids and names that start with it, then the rest in the order of the list.
 * @param {Entry[]} list
 * @param {string} query
 * @param {number} [limit]
 */
export function search(list, query, limit = 60) {
  const text = query.trim().toLowerCase().replace(/^minecraft:/, '')
  const words = text.split(/[\s_]+/).filter(Boolean)
  if (words.length === 0) return list.slice(0, limit)
  const scored = []
  for (const entry of list) {
    const id = entry.id.toLowerCase()
    const name = entry.name.toLowerCase()
    const haystack = `${id} ${name}`.replace(/_/g, ' ')
    if (!words.every((w) => haystack.includes(w))) continue
    let rank = 4
    if (id === text) rank = 0
    else if (id.startsWith(text) || name.startsWith(text)) rank = 1
    else if (words.every((w) => id.replace(/_/g, ' ').split(' ').some((part) => part.startsWith(w)) || name.split(' ').some((part) => part.startsWith(w)))) rank = 2
    else rank = 3
    scored.push({ entry, rank, index: scored.length })
  }
  scored.sort((a, b) => a.rank - b.rank || a.index - b.index)
  return scored.slice(0, limit).map((s) => s.entry)
}

/**
 * The ids the checks look material up in: the items of the game and, by source, those of each plugin that listed its own. Null when the list of the game is too short to be
 * the whole of it (a list that could not be read), so that no item is said to be missing for that.
 * @param {Entry[]} list
 */
export function knownItems(list) {
  const vanilla = new Set()
  const custom = new Map()
  for (const entry of list) {
    if (entry.kind === 'vanilla') vanilla.add(entry.id)
    else if (entry.source) {
      const prefix = entry.source.toLowerCase()
      if (!custom.has(prefix)) custom.set(prefix, new Set())
      custom.get(prefix).add(entry.id.slice(entry.source.length + 1))
    }
  }
  return vanilla.size >= 500 ? { vanilla, custom } : null
}

/** Loads the list once; [server] is the connected server, or null. @returns {Promise<Entry[]>} */
export async function loadCatalog(server, assets) {
  const lang = (await assets.json('minecraft', 'lang', 'en_us')) ?? {}
  if (server) {
    try {
      const data = await server.catalog()
      return entries(data.vanilla ?? [], data.custom ?? {}, lang)
    } catch { /* an older plugin: use the public list */ }
  }
  try {
    const response = await fetch(`https://api.github.com/repos/InventivetalentDev/minecraft-assets/git/trees/${assetSettings.version}:assets/minecraft/items`)
    if (response.ok) return entries((await response.json()).tree.map((f) => f.path.replace(/\.json$/, '')).sort(), {}, lang)
  } catch { /* offline or rate limited */ }
  // The language file names every item and block; that is nearly the same list.
  const ids = [...new Set(Object.keys(lang).filter((k) => /^(item|block)\.minecraft\.[a-z0-9_]+$/.test(k)).map((k) => k.split('.')[2]))].sort()
  return entries(ids, {}, lang)
}
