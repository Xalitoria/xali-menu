// Items the game draws with a model that is written in code, not in a json: heads, chests, shulker boxes. Each is built here
// out of boxes with the same texture layout as the game's, and placed with the display transform of its `base` model, so it goes
// through the same pipeline as every other item.
//
// A box has the texture layout of the game's cubes: for a box of size (x, y, z) at texture offset (u, v) the top, bottom and the four
// sides are laid out in a strip of 2x + 2z by y + z pixels. The front of a model is its south face here, which is where the game's
// models of this kind show it in the inventory.

import { Model, bakeElements } from './bake.js'

/**
 * A box as faces of an element, in sixteenths of a block with y up.
 * @param {number[]} from the lowest corner
 * @param {number[]} size x, y and z
 * @param {number[]} offset the texture offset (u, v) in texture pixels
 * @param {number[]} sheet the size of the texture
 * @param {string} texture `#name` of the texture of the model
 * @param {{grow?: number, tintindex?: number, swapCaps?: boolean, turned?: boolean, extent?: number[]|null}} [options] [grow] puts the box that many pixels outward on every side (the hat of a head);
 *   [extent] is the size of the box when it differs from the size of its texture layout (a model scaled by the item definition);
 *   [swapCaps] swaps the textures of the top and the bottom, and [turned] turns the four sides half way round, for the models of the game that
 *   are built the other way up and round (the chest)
 */
export function box(from, size, offset, sheet, texture, { grow = 0, tintindex, swapCaps = false, turned = false, extent = null } = {}) {
  const [dx, dy, dz] = size
  const reach = extent ?? size
  const [u, v] = offset
  const unit = (px, axis) => (axis === 0 ? (px * 16) / sheet[0] : (px * 16) / sheet[1])
  const region = (u1, v1, u2, v2) => [unit(u1, 0), unit(v1, 1), unit(u2, 0), unit(v2, 1)]
  const face = (rect) => ({ texture, uv: region(...rect), ...(tintindex === undefined ? {} : { tintindex }) })
  const front = [u + dz, v + dz, u + dz + dx, v + dz + dy]
  const back = [u + 2 * dz + dx, v + dz, u + 2 * dz + 2 * dx, v + dz + dy]
  const right = [u, v + dz, u + dz, v + dz + dy]
  const left = [u + dz + dx, v + dz, u + 2 * dz + dx, v + dz + dy]
  return {
    from: from.map((c) => c - grow),
    to: from.map((c, i) => c + reach[i] + grow),
    faces: {
      south: face(turned ? back : front),
      north: face(turned ? front : back),
      west: face(turned ? left : right),
      east: face(turned ? right : left),
      up: face(swapCaps ? [u + dz + dx, v, u + dz + 2 * dx, v + dz] : [u + dz, v, u + dz + dx, v + dz]),
      down: face(swapCaps ? [u + dz, v, u + dz + dx, v + dz] : [u + dz + dx, v, u + dz + 2 * dx, v + dz]),
    },
  }
}

const SKINS = {
  player: { texture: 'minecraft:entity/player/wide/steve', sheet: [64, 64], hat: true },
  zombie: { texture: 'minecraft:entity/zombie/zombie', sheet: [64, 64], hat: true },
  skeleton: { texture: 'minecraft:entity/skeleton/skeleton', sheet: [64, 32], hat: false },
  wither_skeleton: { texture: 'minecraft:entity/skeleton/wither_skeleton', sheet: [64, 32], hat: false },
  creeper: { texture: 'minecraft:entity/creeper/creeper', sheet: [64, 32], hat: false },
}

/**
 * The model of a head: an 8 pixel cube on the bottom of the block, centred, with the hat layer around it for the kinds that have one.
 * @param {string} kind `player`, `zombie`, `skeleton`, `wither_skeleton` or `creeper`
 * @param {string} [texture] another skin for the head, as a texture key
 * @returns {{model: Model, textures: string[]}|null} null for a kind that is not built here (the piglin and dragon heads)
 */
export function headModel(kind, texture) {
  const skin = SKINS[kind]
  if (!skin) return null
  const key = texture ?? skin.texture
  const elements = [box([4, 0, 4], [8, 8, 8], [0, 0], skin.sheet, '#t')]
  if (skin.hat) elements.push(box([4, 0, 4], [8, 8, 8], [32, 0], skin.sheet, '#t', { grow: 0.25 }))
  return { model: new Model({ textures: { t: key }, elements }), textures: [key] }
}

const CHESTS = {
  normal: 'minecraft:entity/chest/normal',
  trapped: 'minecraft:entity/chest/trapped',
  ender: 'minecraft:entity/chest/ender',
  christmas: 'minecraft:entity/chest/christmas',
  copper: 'minecraft:entity/chest/copper',
}

/** A chest: the body, the lid on top of it, and the latch on the front. [texture] is the texture key of the chest (`minecraft:normal`). */
export function chestModel(texture) {
  const name = String(texture ?? 'minecraft:normal').replace(/^minecraft:/, '')
  const key = CHESTS[name] ?? `minecraft:entity/chest/${name}`
  const sheet = [64, 64]
  const elements = [
    box([1, 0, 1], [14, 10, 14], [0, 19], sheet, '#t', { swapCaps: true, turned: true }),
    box([1, 9, 1], [14, 5, 14], [0, 0], sheet, '#t', { swapCaps: true, turned: true }),
    box([7, 7, 15], [2, 4, 1], [0, 0], sheet, '#t', { swapCaps: true, turned: true }),
  ]
  return { model: new Model({ textures: { t: key }, elements }), textures: [key] }
}

/** A shulker box: the base, and the lid over it. [texture] is `minecraft:shulker` or `minecraft:shulker_white` and so on. */
export function shulkerModel(texture) {
  const name = String(texture ?? 'minecraft:shulker').replace(/^minecraft:/, '')
  const key = `minecraft:entity/shulker/${name}`
  const sheet = [64, 64]
  const elements = [
    box([0, 0, 0], [16, 8, 16], [0, 28], sheet, '#t'),
    box([0, 4, 0], [16, 12, 16], [0, 0], sheet, '#t'),
  ]
  return { model: new Model({ textures: { t: key }, elements }), textures: [key] }
}

/**
 * The quads for a `special` model of an item definition, or null if this kind is not built here.
 * @param {{type: string, kind?: string, texture?: string}} special
 * @param {string|null} [skin] a texture key for a player head with a skin of its own
 */
export function specialQuads(special, skin = null) {
  const type = String(special?.type ?? '').replace(/^minecraft:/, '')
  let built = null
  if (type === 'player_head') built = headModel('player', skin ?? undefined)
  else if (type === 'head') built = headModel(special.kind === 'player' ? 'player' : (special.kind ?? 'player'), special.kind === 'player' ? skin ?? undefined : undefined)
  else if (type === 'chest') built = chestModel(special.texture)
  else if (type === 'shulker_box') built = shulkerModel(special.texture)
  return built ? { quads: bakeElements(built.model), textures: built.textures } : null
}

/** The colors of the dyes as banners use them. */
export const DYES = {
  white: 0xf9fffe, orange: 0xf9801d, magenta: 0xc74ebd, light_blue: 0x3ab3da, yellow: 0xfed83d, lime: 0x80c71f, pink: 0xf38baa, gray: 0x474f52,
  light_gray: 0x9d9d97, cyan: 0x169c9c, purple: 0x8932b8, blue: 0x3c44aa, brown: 0x835432, green: 0x5e7c16, red: 0xb02e26, black: 0x1d1d21,
}

/**
 * The layers of a banner: the pole, the bar and the cloth, then a layer of the cloth for each pattern in its own color. The model is
 * the game's (a 20 by 40 cloth on a pole and a bar) with the scale the item definition gives it (two thirds, upside down and
 * turned, as an entity model is), put on the block so that the display transform of the base model applies as to any other.
 * @param {string} color the color of the banner (`white`)
 * @param {{color: string, pattern: string}[]} [patterns]
 * @returns {{layers: {quads: import('./bake.js').Quad[], tints: number[]}[], textures: string[]}}
 */
export function bannerLayers(color, patterns = []) {
  const k = 2 / 3
  // entity coordinates (pixels, y down) to the block: scale (k, -k, -k), and the middle of the block in all three directions
  const at = (x, y, z) => [x * k + 8, -y * k + 8, -z * k + 8]
  const sheet = [64, 64]
  const cloth = (texture, tintindex) => {
    const from = at(-10, 8, -1) // the low corner: the cloth hangs from y = -32 to 8, in front of z = -2..-1
    const element = box([from[0], from[1], from[2]], [20, 40, 1], [0, 0], sheet, '#t', { tintindex, extent: [20 * k, 40 * k, 1 * k] })
    return new Model({ textures: { t: texture }, elements: [element] })
  }
  const pole = (() => {
    const lowPole = at(-1, 12, 1)
    const lowBar = at(-10, -30, 1)
    return new Model({ textures: { t: 'minecraft:entity/banner/base' }, elements: [
      box(lowPole, [2, 42, 2], [44, 0], sheet, '#t', { tintindex: 0, extent: [2 * k, 42 * k, 2 * k] }),
      box(lowBar, [20, 2, 2], [0, 42], sheet, '#t', { tintindex: 0, extent: [20 * k, 2 * k, 2 * k] }),
    ] })
  })()
  const dye = DYES[color] ?? DYES.white
  const layers = [{ quads: bakeElements(cloth('minecraft:entity/banner/base', 0)), tints: [dye] }, { quads: bakeElements(pole), tints: [dye] }]
  const textures = ['minecraft:entity/banner/base']
  for (const { color: c, pattern } of patterns) {
    const name = String(pattern ?? '').replace(/^minecraft:/, '')
    const key = `minecraft:entity/banner/${name}`
    layers.push({ quads: bakeElements(cloth(key, 0)), tints: [DYES[String(c).toLowerCase()] ?? DYES.white] })
    textures.push(key)
  }
  return { layers, textures }
}

/**
 * The layers of a shield: the plate with its handle in the color of the base, then the plate again for each pattern in its own color. The
 * model is the game's (a 12 by 22 plate, one pixel thick, and a handle behind it) turned upside down and round the way an entity model is, put on the
 * block so that the display transform of the base model applies. A shield with no pattern and no base color has a plain texture of its own.
 * @param {string|null} baseColor the dye of the base, if it has one
 * @param {{color: string, pattern: string}[]} [patterns]
 * @returns {{layers: {quads: import('./bake.js').Quad[], tints: number[]}[], textures: string[]}}
 */
export function shieldLayers(baseColor, patterns = []) {
  const at = (x, y, z) => [x + 8, -y + 8, -z + 8]
  const sheet = [64, 64]
  const plate = (texture, tintindex) => new Model({ textures: { t: texture }, elements: [
    box(at(-6, 11, -1), [12, 22, 1], [0, 0], sheet, '#t', { tintindex }),
  ] })
  const handle = (texture, tintindex) => new Model({ textures: { t: texture }, elements: [
    box(at(-1, 3, 5), [2, 6, 6], [26, 0], sheet, '#t', { tintindex }),
  ] })
  const plain = !baseColor && patterns.length === 0
  const base = plain ? 'minecraft:entity/shield/shield_base_nopattern' : 'minecraft:entity/shield/shield_base'
  const tint = plain ? [] : [DYES[String(baseColor ?? 'white').toLowerCase()] ?? DYES.white]
  const index = plain ? undefined : 0
  const layers = [
    { quads: [...bakeElements(plate(base, index)), ...bakeElements(handle(base, index))], tints: tint },
  ]
  const textures = [base]
  for (const { color, pattern } of patterns) {
    const key = `minecraft:entity/shield/${String(pattern ?? '').replace(/^minecraft:/, '')}`
    layers.push({ quads: bakeElements(plate(key, 0)), tints: [DYES[String(color).toLowerCase()] ?? DYES.white] })
    textures.push(key)
  }
  return { layers, textures }
}
