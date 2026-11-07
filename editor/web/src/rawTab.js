// The "Raw" tab: the menu as text. The text is a draft of its own: what is typed in it reaches the editor when it is applied (Ctrl+S or the
// button), and what is changed in the editor is in the text the next time the tab is opened, so the two never fight over the menu.

import { h } from './dom.js'
import { parse } from './hocon.js'
import { write } from './hoconWriter.js'

export class RawTab {
  /** @param {import('./app.js').App} app */
  constructor(app) {
    this.app = app
    /** The text that was typed and not applied, or null when the text is what the editor has. */
    this.draft = null
    /** What the line under the buttons says, and whether it is an error. */
    this.note = null
  }

  get pending() { return this.draft !== null }

  /** Forgets the draft: the menu was replaced (another menu opened, an undo). */
  discard() {
    this.draft = null
    this.note = null
  }

  /**
   * Reads the draft into the editor. Returns false when the text cannot be read (the line under the buttons says why and the draft stays).
   * A text that says what the editor already has changes nothing.
   */
  apply() {
    if (!this.pending) return true
    let menu
    try { menu = parse(this.draft) } catch (error) { this.note = { text: error.message, bad: true }; return false }
    this.draft = null
    this.note = { text: 'Applied to the editor.', bad: false }
    if (JSON.stringify(menu) !== JSON.stringify(this.app.store.value)) this.app.store.set([], menu)
    return true
  }

  view() {
    const { app } = this
    const area = h('textarea', { class: 'raw', spellcheck: false, value: this.draft ?? write(app.store.value), 'aria-label': 'The menu as text' })
    const message = h('span', { class: 'raw-note' })
    const state = h('span', { class: 'raw-state' })
    const show = () => {
      state.textContent = this.pending ? '● Not applied yet' : 'The same as the editor'
      state.className = `raw-state ${this.pending ? 'pending' : ''}`
      message.textContent = this.note?.text ?? ''
      message.className = `raw-note ${this.note?.bad ? 'bad' : 'muted'}`
      area.classList.toggle('bad', Boolean(this.note?.bad))
    }
    let timer = 0
    area.addEventListener('input', () => {
      this.draft = area.value
      this.note = null
      show()
      // Say at once when the text cannot be read, without waiting for the apply.
      clearTimeout(timer)
      timer = setTimeout(() => { try { parse(area.value) } catch (error) { this.note = { text: error.message, bad: true }; show() } }, 400)
    })
    show()
    return h('div', { class: 'raw-tab' },
      h('div', { class: 'raw-bar' },
        h('button', { class: 'primary', title: 'Read the text into the editor (Ctrl+S does this and saves)', onclick: () => { this.apply(); app.draw() } }, 'Apply to the editor'),
        h('button', { title: 'Put the text of the editor back', disabled: !this.pending, onclick: () => { this.discard(); app.draw() } }, 'Revert the text'),
        state, message),
      area)
  }
}
