// The color a potion shows (the tint of a potion, a splash potion, a lingering potion and a tipped arrow): the average of the colors of its effects, each as
// often as its level, and the color of water when it has none. A connected server works it out itself and is believed; this is what the editor
// uses without one, from the colors of the effects of the game, so that an arrow changes its tint as soon as its potion type does.

/** The color of water, which a potion with no effects has. */
export const WATER = 0x385dc6

/** The colors of the effects of the game, as 0xRRGGBB. */
export const EFFECT_COLORS = {
  speed: 0x33ebff, slowness: 0x8bafe0, haste: 0xd9c043, mining_fatigue: 0x4a4217, strength: 0xffc700, instant_health: 0xf82423, instant_damage: 0xa9656a,
  jump_boost: 0xfdff84, nausea: 0x551d4a, regeneration: 0xcd5cab, resistance: 0x9146f0, fire_resistance: 0xff9900, water_breathing: 0x98dac0,
  invisibility: 0x7f8392, blindness: 0x1f1f23, night_vision: 0x1f1fa1, hunger: 0x587653, weakness: 0x484d48, poison: 0x87a363, wither: 0x352a27,
  health_boost: 0xf87d23, absorption: 0x2552a5, saturation: 0xf82423, glowing: 0x94a061, levitation: 0xceffff, luck: 0x59c106, unluck: 0xc0a44d,
  slow_falling: 0xf3cfb9, conduit_power: 0x1dc2d1, dolphins_grace: 0x88a3be, bad_omen: 0x0b6138, hero_of_the_village: 0x44ff44, darkness: 0x292721,
  wind_charged: 0xbdc9ff, weaving: 0x78695a, oozing: 0xc3d96a, infested: 0x8c9b8c,
}

/** The effects of each base potion, as `[effect, level]` (the level counts from 1); a `long_` or `strong_` potion has those of the plain one. */
const POTIONS = {
  night_vision: [['night_vision', 1]], invisibility: [['invisibility', 1]], leaping: [['jump_boost', 1]], fire_resistance: [['fire_resistance', 1]],
  swiftness: [['speed', 1]], slowness: [['slowness', 1]], turtle_master: [['slowness', 4], ['resistance', 3]], water_breathing: [['water_breathing', 1]],
  healing: [['instant_health', 1]], harming: [['instant_damage', 1]], poison: [['poison', 1]], regeneration: [['regeneration', 1]], strength: [['strength', 1]],
  weakness: [['weakness', 1]], luck: [['luck', 1]], slow_falling: [['slow_falling', 1]], wind_charged: [['wind_charged', 1]], weaving: [['weaving', 1]],
  oozing: [['oozing', 1]], infested: [['infested', 1]],
}

/** The name without a namespace and without `long_` or `strong_`, and whether it was `strong_` (the level of its effects is one higher). */
function baseName(type) {
  const name = String(type).trim().toLowerCase().replace(/^minecraft:/, '')
  if (name.startsWith('strong_')) return { name: name.slice(7), strong: true }
  return { name: name.startsWith('long_') ? name.slice(5) : name, strong: false }
}

/**
 * The color of a potion of the base type [type] (such as `strong_healing`, or none) with the custom [effects] (`{type, amplifier}`, the amplifier being the
 * level minus one), as 0xRRGGBB; null when a type is named that is not one of the game's, since then the color is not known.
 */
export function potionColor(type, effects = []) {
  const parts = []
  if (type) {
    const { name, strong } = baseName(type)
    if (name === 'water' || name === 'mundane' || name === 'thick' || name === 'awkward') { /* no effects */ } else if (POTIONS[name]) {
      for (const [effect, level] of POTIONS[name]) parts.push([effect, level + (strong ? 1 : 0)])
    } else return null
  }
  for (const effect of effects) {
    const name = String(effect.type ?? '').trim().toLowerCase().replace(/^minecraft:/, '')
    if (EFFECT_COLORS[name] !== undefined) parts.push([name, (effect.amplifier ?? 0) + 1])
  }
  let red = 0, green = 0, blue = 0, weight = 0
  for (const [effect, level] of parts) {
    const color = EFFECT_COLORS[effect]
    red += ((color >> 16) & 255) * level; green += ((color >> 8) & 255) * level; blue += (color & 255) * level; weight += level
  }
  if (weight === 0) return WATER
  return ((Math.trunc(red / weight) << 16) | (Math.trunc(green / weight) << 8) | Math.trunc(blue / weight)) >>> 0
}
