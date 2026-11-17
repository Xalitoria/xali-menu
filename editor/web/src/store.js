// Holds the menu being edited as plain JSON, with undo and redo. Every change goes through here so the views only have to
// listen and redraw.

const LIMIT = 100

export class Store {
  constructor(value = {}) {
    this.value = value
    this.undoStack = []
    this.redoStack = []
    this.listeners = new Set()
    /** Writes a menu that comes in with its easy forms the one way: set by the page, which has the schema. */
    this.normalizer = null
    this.saved = JSON.stringify(value)
  }

  get dirty() { return JSON.stringify(this.value) !== this.saved }

  markSaved() { this.saved = JSON.stringify(this.value); this.emit() }

  onChange(listener) { this.listeners.add(listener); return () => this.listeners.delete(listener) }

  /** Tells the views. [quiet] is true for a change the view that made it has already shown (typing in a field). */
  emit(quiet = false) { for (const listener of this.listeners) listener(quiet) }

  /** Replaces the whole menu without making it an undo step (loading another menu). */
  load(value) {
    value = this.normalizer ? this.normalizer(value) : value
    this.value = value
    this.undoStack = []
    this.redoStack = []
    this.saved = JSON.stringify(value)
    this.emit()
  }

  /** Reads the value at [path], a list of keys and indexes. */
  get(path, from = this.value) {
    return path.reduce((node, key) => (node === null || node === undefined ? undefined : node[key]), from)
  }

  /** Sets the value at [path]. The same value again is not a change. */
  set(path, value, quiet = false) {
    if (path.length === 0) return this.change((draft) => value, quiet)
    this.change((draft) => {
      const parent = this.get(path.slice(0, -1), draft)
      const key = path[path.length - 1]
      if (JSON.stringify(parent[key]) === JSON.stringify(value)) return null
      parent[key] = value
      return draft
    }, quiet)
  }

  remove(path) {
    this.change((draft) => {
      const parent = this.get(path.slice(0, -1), draft)
      const key = path[path.length - 1]
      if (Array.isArray(parent)) parent.splice(key, 1)
      else delete parent[key]
      return draft
    })
  }

  /** Moves the entry at [index] of the list at [path] by [delta] places. */
  move(path, index, delta) {
    this.change((draft) => {
      const list = this.get(path, draft)
      const to = index + delta
      if (to < 0 || to >= list.length) return null
      list.splice(to, 0, list.splice(index, 1)[0])
      return draft
    })
  }

  /** Renames the key [from] of the object at [path], keeping the order of the keys. */
  rename(path, from, to) {
    this.change((draft) => {
      const object = this.get(path, draft)
      if (from === to || to in object) return null
      const entries = Object.entries(object).map(([k, v]) => [k === from ? to : k, v])
      for (const k of Object.keys(object)) delete object[k]
      for (const [k, v] of entries) object[k] = v
      return draft
    })
  }

  /** Runs [edit] on a copy; it returns the new value, or null for no change. */
  change(edit, quiet = false) {
    const draft = structuredClone(this.value)
    const result = edit(draft)
    if (result === null || result === undefined) return
    this.undoStack.push(this.value)
    if (this.undoStack.length > LIMIT) this.undoStack.shift()
    this.redoStack = []
    this.value = this.normalizer ? this.normalizer(result) : result
    this.emit(quiet)
  }

  undo() {
    if (this.undoStack.length === 0) return
    this.redoStack.push(this.value)
    this.value = this.undoStack.pop()
    this.emit()
  }

  redo() {
    if (this.redoStack.length === 0) return
    this.undoStack.push(this.value)
    this.value = this.redoStack.pop()
    this.emit()
  }
}
