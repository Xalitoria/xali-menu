// A text box for the material of an item that offers a search of every item there is as you type: the items of the game and the custom items
// of the server, with their pictures. What is typed is the value whether or not it is in the list (a head, a texture, an item of a plugin the
// editor cannot list); the list is only a shortcut.

import { h, fill } from './dom.js'
import { search } from './catalog.js'
import { icon } from './icons.js'
import { services, catalog } from './services.js'

const MORE = 60

/**
 * @param {{value?: string, placeholder?: string, commit: (value: string, final: boolean) => void}} options
 *   [commit] is told the value on every key (`final` false), and when one is picked from the list or the box is left (`final` true)
 * @returns {HTMLElement}
 */
export function materialPicker({ value = '', placeholder = '', commit }) {
  const input = h('input', { type: 'text', value, placeholder, autocomplete: 'off', spellcheck: false, class: 'picker-input' })
  const list = h('div', { class: 'picker-list', hidden: true, role: 'listbox' })
  const box = h('div', { class: 'picker-box' }, input, list)
  let shown = []
  let active = 0
  let entries = null

  const observer = new IntersectionObserver((seen) => {
    for (const hit of seen) {
      if (!hit.isIntersecting) continue
      observer.unobserve(hit.target)
      const slot = hit.target.querySelector('.picker-icon')
      const id = hit.target.dataset.id
      if (slot && !slot.firstChild) slot.append(icon({ material: id }, 2, 1, { contain: true }))
    }
  }, { root: list })

  function render() {
    observer.disconnect()
    shown = entries ? search(entries, input.value, MORE) : []
    active = Math.min(active, Math.max(0, shown.length - 1))
    fill(list,
      !entries && h('div', { class: 'picker-note' }, 'Loading the items…'),
      entries && shown.length === 0 && h('div', { class: 'picker-note' }, input.value ? 'No item has that name. What you type is used as it is.' : 'No items.'),
      shown.map((entry, i) => {
        const row = h('div', { class: `picker-row ${i === active ? 'active' : ''}`, 'data-id': entry.id, role: 'option',
          onmousedown: (e) => { e.preventDefault(); choose(entry.id) }, onmousemove: () => { if (active !== i) { active = i; mark() } } },
          h('span', { class: 'picker-icon' }), h('span', { class: 'picker-name' }, entry.name), h('code', null, entry.id), entry.kind === 'custom' && h('span', { class: 'badge' }, entry.source))
        // The first rows have their picture at once; the others when they are scrolled to.
        if (i < 10) row.querySelector('.picker-icon').append(icon({ material: entry.id }, 2, 1, { contain: true }))
        else observer.observe(row)
        return row
      }),
      shown.length === MORE && h('div', { class: 'picker-note' }, 'Type more to narrow the list.'))
  }

  function mark() {
    list.querySelectorAll('.picker-row').forEach((row, i) => row.classList.toggle('active', i === active))
    list.querySelector('.picker-row.active')?.scrollIntoView({ block: 'nearest' })
  }

  function open() {
    list.hidden = false
    render()
    if (!entries) catalog().then((loaded) => { entries = loaded; if (!list.hidden) render() })
  }

  function close() { list.hidden = true }

  function choose(id) {
    input.value = id
    close()
    commit(id, true)
  }

  input.addEventListener('focus', open)
  input.addEventListener('input', () => { active = 0; if (list.hidden) open(); else render(); commit(input.value, false) })
  input.addEventListener('blur', () => { close(); commit(input.value, true) })
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); if (list.hidden) open(); active = Math.min(shown.length - 1, active + 1); mark() }
    else if (e.key === 'ArrowUp') { e.preventDefault(); active = Math.max(0, active - 1); mark() }
    else if (e.key === 'Enter' && !list.hidden && shown[active]) { e.preventDefault(); choose(shown[active].id) }
    else if (e.key === 'Escape') close()
  })
  return box
}
