// Says an action or a condition as a sentence ("Gives 5 diamonds", "The player has the permission shop.use"), so that a list of them reads as what happens and not as
// keys. It has no DOM and takes the value as the menu holds it; a type it does not know (an addon's) is left as its name.

import { plain } from './mctext.js'

const text = (v, max = 40) => {
  const s = plain(String(v ?? '')).replace(/\s+/g, ' ').trim()
  return `“${s.length > max ? `${s.slice(0, max - 1)}…` : s}”`
}
const plural = (n, one, many = `${one}s`) => `${n} ${Number(n) === 1 ? one : many}`
const count = (list, one, many) => plural(Array.isArray(list) ? list.length : 0, one, many)
const has = (v) => v !== undefined && v !== null && v !== ''
const list = (items) => (Array.isArray(items) ? items.map(String) : []).join(', ')
const lower = (s) => String(s ?? '').toLowerCase().replace(/_/g, ' ')
const key = (s) => String(s ?? '').replace(/^minecraft:/, '').replace(/_/g, ' ')

/** Ticks as the game's clock says them: `20` is a second. Text with a placeholder is left as written. */
const ticks = (n) => {
  const t = Number(n)
  if (!Number.isFinite(t)) return String(n)
  if (t % 20 === 0) return plural(t / 20, 'second')
  return plural(t, 'tick')
}

const nested = (value, sentence) => (value && typeof value === 'object' ? sentence(value) : '…')

/** Gives or takes by `take`. */
const gives = (a, what) => `${a.take ? 'Takes' : 'Gives'} ${what}`

/** The sentence of an action (`{type: 'close'}`...). */
export function action(a) {
  if (!a || typeof a !== 'object' || !a.type) return ''
  switch (a.type) {
    case 'actionbar': return `Shows ${text(a.text)} above the hotbar`
    case 'all': return `Runs ${count(a.actions, 'action')}`
    case 'book': return `Opens a book of ${count(a.pages, 'page')}`
    case 'boss-bar': return `Shows the boss bar ${text(a.text)} for ${a.duration ?? '5s'}`
    case 'broadcast': return `Tells everyone ${text(a.text)}`
    case 'chance': return `With a ${a.percent ?? '?'}% chance: ${nested(a.action, action)}`
    case 'chat': return `Makes the player say ${text(a.text)}`
    case 'clear-items': return has(a.material) ? `Takes every ${key(a.material)} from the player` : has(a.name) || has(a.model) ? 'Takes every matching item from the player' : 'Empties the inventory of the player'
    case 'close': return 'Closes the menu'
    case 'connect': return `Sends the player to the server ${a.server ?? '?'}`
    case 'console': return `Runs /${String(a.command ?? '').replace(/^\//, '')} as the console`
    case 'cooldown': return `Starts a ${a.duration ?? ''} cooldown ${text(a.key)}${a.scope === 'GLOBAL' ? ' for everyone' : ''}`.replace('a  cooldown', 'a cooldown')
    case 'delay': return `After ${ticks(a.ticks)}: ${nested(a.action, action)}`
    case 'experience': return `${gives(a, `${a.amount ?? '?'} experience points`)}`
    case 'food': return `${a.change === 'ADD' ? 'Adds' : a.change === 'TAKE' ? 'Takes' : 'Sets the food to'} ${a.amount ?? '?'}${a.change === 'ADD' ? ' food' : a.change === 'TAKE' ? ' food' : ''}`
    case 'gamemode': return `Sets the game mode to ${lower(a.mode)}`
    case 'give-item': {
      const item = a.item ?? {}
      const what = has(item.name) ? text(item.name) : key(item.material)
      return `Gives ${what || 'an item'}${has(item.amount) && item.amount !== 1 ? ` × ${item.amount}` : ''}`
    }
    case 'group': return `${a.take ? 'Removes the player from' : 'Adds the player to'} the group ${a.name ?? '?'}`
    case 'health': return `${a.change === 'ADD' ? 'Heals by' : a.change === 'TAKE' ? 'Hurts by' : 'Sets the health to'} ${a.amount ?? '?'}`
    case 'input': return `Asks the player for text (${text(a.prompt, 30)}), then runs ${count(a.then, 'action')}`
    case 'json': return a.broadcast ? 'Tells everyone a JSON text' : 'Tells the player a JSON text'
    case 'level': return gives(a, `${a.amount ?? '?'} levels`)
    case 'log': return `Writes ${text(a.text)} in the log`
    case 'message': return `Tells the player ${text(a.text)}`
    case 'meta': return `Sets the meta ${a.key ?? '?'} of the player to ${has(a.value) ? text(a.value) : 'nothing'}`
    case 'money': return gives(a, `${a.amount ?? '?'} ${a.currency ?? 'money'}`)
    case 'open': return `Opens the menu ${a.menu ?? '?'}${Array.isArray(a.args) && a.args.length ? ` with ${list(a.args)}` : ''}`
    case 'page': return has(a.to) ? `Goes to page ${a.to}` : Number(a.by) === 1 ? 'Goes to the next page' : Number(a.by) === -1 ? 'Goes to the previous page' : `Goes ${Number(a.by) > 0 ? 'forward' : 'back'} ${Math.abs(Number(a.by) || 0)} pages`
    case 'particle': return `Shows ${a.count ?? 10} ${key(a.particle)} particles${a.everyone ? ' to everyone' : ''}`
    case 'permission': return `${a.take ? 'Takes' : 'Gives'} the permission ${a.node ?? '?'}`
    case 'player': return `Runs /${String(a.command ?? '').replace(/^\//, '')} as the player`
    case 'potion': return a.remove ? `Removes the effect ${key(a.effect)}` : `Gives ${key(a.effect)} level ${(Number(a.amplifier) || 0) + 1} for ${a.duration ?? '30s'}`
    case 'random': return `Runs ${a.count > 1 ? `${a.count} of` : 'one of'} ${count(a.actions, 'action')}, picked at random`
    case 'refresh': return 'Draws the menu again'
    case 'run-for': return `For each ${a.catalog?.type ? `value of the ${a.catalog.type} catalog` : 'value'}: runs ${count(a.actions, 'action')}`
    case 'skin': return `Gives the player the skin ${a.skin ?? '?'}`
    case 'skin-reset': return 'Gives the player their own skin back'
    case 'sound': return `Plays ${key(a.sound)}${a.scope === 'WORLD' ? ' in the world' : a.scope === 'SERVER' ? ' to everyone' : ''}`
    case 'stop': return 'Stops the actions after it'
    case 'take-item': return `Takes ${key(a.material) || 'a matching item'}${Number(a.amount ?? 1) !== 1 ? ` × ${a.amount}` : ''}, and stops if the player has fewer`
    case 'teleport': return `Teleports the player to ${[a.x, a.y, a.z].every(has) ? `${a.x} ${a.y} ${a.z}` : 'a place'}${has(a.world) ? ` in ${a.world}` : ''}`
    case 'title': return `Shows the title ${text([a.title, a.subtitle].filter(has).join(' / '))}`
    case 'variable': return variable(a)
    default: return a.type
  }
}

function variable(a) {
  const name = a.name ?? '?'
  const value = has(a.value) ? a.value : '?'
  switch (a.op) {
    case 'REMOVE': return `Removes the variable ${name}`
    case 'INC': return `Adds ${value} to the variable ${name}`
    case 'DEC': return `Takes ${value} from the variable ${name}`
    case 'MUL': return `Multiplies the variable ${name} by ${value}`
    case 'DIV': return `Divides the variable ${name} by ${value}`
    default: return `Sets the variable ${name} to ${value}`
  }
}

const OPS = { GT: 'is more than', GTE: 'is at least', EQ: 'is', NEQ: 'is not', LT: 'is less than', LTE: 'is at most' }

/** `min` and `max` as "at least 5", "between 5 and 10". */
function between(min, max, unit) {
  if (has(min) && has(max)) return `between ${min} and ${max}${unit}`
  if (has(min)) return `at least ${min}${unit}`
  if (has(max)) return `at most ${max}${unit}`
  return `any amount${unit}`
}

/** The sentence of a condition. */
export function condition(c) {
  if (!c || typeof c !== 'object' || !c.type) return ''
  switch (c.type) {
    case 'and': return `All of ${count(c.conditions, 'condition')} hold`
    case 'at-least': return `At least ${c.count ?? '?'} of ${count(c.conditions, 'condition')} hold`
    case 'chance': return `By chance, ${c.percent ?? '?'}% of the time`
    case 'client': {
      const parts = [
        has(c.edition) && lower(c.edition),
        Array.isArray(c.devices) && c.devices.length && list(c.devices.map(lower)),
        Array.isArray(c.os) && c.os.length && list(c.os.map(lower)),
        Array.isArray(c.inputs) && c.inputs.length && list(c.inputs.map(lower)),
        Array.isArray(c.ui) && c.ui.length && `${list(c.ui.map(lower))} UI`,
      ].filter(Boolean)
      return parts.length ? `The player's client is ${parts.join(', ')}` : 'The player matches a client filter'
    }
    case 'compare': return `${c.left ?? '?'} ${OPS[c.op] ?? c.op ?? '?'} ${c.right ?? '?'}`
    case 'cooldown': return `The cooldown ${text(c.key)} is over`
    case 'experience': return `The player has ${between(c.min, c.max, ' experience points')}`
    case 'food': return `The player's food is ${between(c.min, c.max, '')}`
    case 'free-slots': return `The player has at least ${c.min ?? 1} free ${Number(c.min ?? 1) === 1 ? 'slot' : 'slots'}`
    case 'gamemode': return `The player is in ${list((c.modes ?? []).map(lower)) || '?'} mode`
    case 'group': return `The player is in the group ${c.name ?? '?'}`
    case 'health': return `The player's health is ${between(c.min, c.max, '')}`
    case 'held-item': return `The player holds ${key(c.material) || 'an item'}${has(c.amount) && c.amount > 1 ? ` × ${c.amount}` : ''}${c.hand === 'OFF_HAND' ? ' in the off hand' : ''}`
    case 'item': return `The player has ${key(c.material) || 'an item'}${has(c.amount) && c.amount > 1 ? ` × ${c.amount}` : ''}`
    case 'level': return `The player's level is ${between(c.min, c.max, '')}`
    case 'money': return `The player has at least ${c.amount ?? '?'} ${c.currency ?? 'money'}`
    case 'near': return `The player is within ${c.distance ?? '?'} blocks of ${c.x ?? '?'} ${c.y ?? '?'} ${c.z ?? '?'} in ${c.world ?? '?'}`
    case 'never': return 'Never holds'
    case 'not': return `Not: ${nested(c.condition, condition)}`
    case 'one-of': return `Exactly one of ${count(c.conditions, 'condition')} holds`
    case 'online': return `${c.player ?? '?'} is online`
    case 'online-count': return `${between(c.min, c.max, ' players')} are online`
    case 'or': return `Any of ${count(c.conditions, 'condition')} holds`
    case 'permission': return `The player has the permission ${c.node ?? '?'}`
    case 'regex': return `${c.input ?? '?'} matches ${c.pattern ?? '?'}`
    case 'region': return Array.isArray(c.names) && c.names.length ? `The player is in the region ${list(c.names)}` : 'The player is in a region'
    case 'text': return `${c.input ?? '?'} ${c.op === 'CONTAINS' ? 'contains' : c.op === 'EQUALS_IGNORE_CASE' ? 'is, in any case,' : 'is'} ${text(c.value)}`
    case 'variable-exists': return `The variable ${c.name ?? '?'} is set`
    case 'world': return `The player is in the world ${list(c.names) || '?'}`
    default: return c.type
  }
}

/** The sentence of [value] as the base [base] (`Action`, `Condition`) says it; empty for any other base. */
export function sentence(base, value) {
  if (base === 'Action') return action(value)
  if (base === 'Condition') return condition(value)
  return ''
}
