// The sentences an action or a condition is edited through: `Tells the player {text}` is a line of words with a box in it. A `{key}` is the control of that key, in
// the place the key has in the sentence; `{take|Gives|Takes}` is a switch told as one of two words (false first). The keys a sentence does not hold stay in the form
// under it, so nothing is lost: the sentence is the short way to what is said most. A type with no sentence here has only the form.

export const TEMPLATES = {
  Action: {
    actionbar: 'Shows {text} above the hotbar',
    broadcast: 'Tells everyone {text}',
    chat: 'Makes the player say {text}',
    message: 'Tells the player {text}',
    console: 'Runs the command {command} as the console',
    player: 'Runs the command {command} as the player',
    connect: 'Sends the player to the server {server}',
    open: 'Opens the menu {menu}',
    delay: 'After {ticks} ticks, runs:',
    chance: 'With a {percent}% chance, runs:',
    sound: 'Plays {sound}',
    gamemode: 'Sets the game mode to {mode}',
    permission: '{take|Gives|Takes} the permission {node}',
    group: '{take|Adds the player to|Removes the player from} the group {name}',
    money: '{take|Gives|Takes} {amount} money',
    experience: '{take|Gives|Takes} {amount} experience points',
    level: '{take|Gives|Takes} {amount} levels',
    health: 'Health: {change} {amount}',
    food: 'Food: {change} {amount}',
    variable: 'Variable {name}: {op} {value}',
    cooldown: 'Starts the cooldown {key} for {duration}',
    title: 'Shows the title {title} and the subtitle {subtitle}',
    log: 'Writes {text} in the log as {level}',
    random: 'Runs {count} of the actions below, picked at random',
    'take-item': 'Takes {amount} of {material}',
    particle: 'Shows {count} {particle} particles',
    potion: 'Gives the effect {effect} for {duration}, amplifier {amplifier}',
    teleport: 'Teleports the player to {x} {y} {z} in {world}',
    page: 'Goes by {by} pages, or to the page {to}',
    input: 'Asks the player for text: {prompt}',
  },
  Condition: {
    permission: 'The player has the permission {node}',
    compare: '{left} {op} {right}',
    chance: '{percent}% of the time',
    cooldown: 'The cooldown {key} is over',
    'free-slots': 'The player has at least {min} free slots',
    group: 'The player is in the group {name}',
    level: 'The level of the player is between {min} and {max}',
    health: 'The health of the player is between {min} and {max}',
    food: 'The food of the player is between {min} and {max}',
    experience: 'The experience of the player is between {min} and {max}',
    'online-count': 'The players online are between {min} and {max}',
    money: 'The player has at least {amount}',
    item: 'The player has {amount} of {material}',
    'held-item': 'The player holds {material}',
    online: '{player} is online',
    'variable-exists': 'The variable {name} is set',
    text: '{input} {op} {value}',
    regex: '{input} matches {pattern}',
    'at-least': 'At least {count} of the conditions below hold',
    near: 'The player is within {distance} blocks of {x} {y} {z} in {world}',
  },
}

/** The sentence of [type] of [base] (`Action`, `Condition`), or null. */
export const templateOf = (base, type) => TEMPLATES[base]?.[type] ?? null

/** A sentence as its parts: `{text}` (words) and `{key, off, on}` (a control; off and on name the two states of a switch). */
export function tokens(template) {
  const out = []
  for (const part of template.split(/(\{[^}]+\})/)) {
    if (!part) continue
    const match = /^\{([^|}]+)(?:\|([^|}]*)\|([^|}]*))?\}$/.exec(part)
    out.push(match ? { key: match[1], off: match[2], on: match[3] } : { text: part })
  }
  return out
}

/** The keys a sentence holds. */
export const keysOf = (template) => tokens(template).filter((t) => t.key).map((t) => t.key)
