// The sixteen dye colors of the game and the row of buttons that picks one.

import { h } from './dom.js'

/** The dye colors of the game as `#rrggbb`, in the order of the palette, with their names as the menu file writes them. */
export const DYES = [
  ['white', '#f9fffe'], ['light_gray', '#9d9d97'], ['gray', '#474f52'], ['black', '#1d1d21'], ['brown', '#835432'], ['red', '#b02e26'], ['orange', '#f9801d'], ['yellow', '#fed83d'],
  ['lime', '#80c71f'], ['green', '#5e7c16'], ['cyan', '#169c9c'], ['light_blue', '#3ab3da'], ['blue', '#3c44aa'], ['purple', '#8932b8'], ['magenta', '#c74ebd'], ['pink', '#f38baa'],
]

/** A name for people: `light_gray` as `Light gray`. */
export const label = (id) => id.replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase())

/** A row of the sixteen dye colors, the chosen one ringed. [upper] is for a file that writes them in upper case (a fish's); either is read. */
export function dyes(current, pick, { upper = false } = {}) {
  const chosen = String(current ?? '').toLowerCase()
  return h('div', { class: 'dyes', role: 'radiogroup' }, DYES.map(([name, hex]) => h('button', {
    class: `dye ${name === chosen ? 'on' : ''}`, style: `background:${hex}`, title: label(name), 'aria-label': label(name), 'aria-checked': name === chosen, role: 'radio',
    onclick: () => pick(upper ? name.toUpperCase() : name) })))
}
