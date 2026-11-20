// A piece of armor as it is worn, seen from the front: the part of the figure it covers, the armor texture and the trim over it, as the game draws them. The textures are
// the game's (or the pack's) own, so a pack that changes the armor, a trim pattern or a palette changes the picture. The planning and the pixels have no DOM, so a test can
// ask for them; `wornCanvas` is the one that draws.

import { Texture } from './assets.js'

/** The slot an item of [material] is worn in (`head`, `chest`, `legs`, `feet`), or null for what is not armor. */
export function slotOf(material) {
  const id = String(material ?? '').trim().toLowerCase().replace(/^minecraft:/, '')
  if (/(^|_)helmet$/.test(id)) return 'head'
  if (/_chestplate$/.test(id)) return 'chest'
  if (/_leggings$/.test(id)) return 'legs'
  if (/_boots$/.test(id)) return 'feet'
  return null
}

const ARMOR_IDS = { golden: 'gold', turtle: 'turtle_scute' }

/** The equipment asset of an armor item: `iron_chestplate` is `iron`, `golden_boots` is `gold`, the turtle shell is `turtle_scute`. */
export function equipmentOf(material) {
  const id = String(material ?? '').trim().toLowerCase().replace(/^minecraft:/, '')
  const prefix = id.replace(/_(helmet|chestplate|leggings|boots)$/, '')
  return ARMOR_IDS[prefix] ?? prefix
}

/**
 * Where each piece of armor is drawn on the front of a 16 by 32 figure, and where it is in the 64 by 32 armor texture: `from` is `[x, y, width, height]` of the texture,
 * `to` the place on the figure, and `mirror` for the left side, which the game draws from the texture of the right one turned over. The leggings are their own texture
 * (`humanoid_leggings`) and cover the belt and the legs; the boots are the lower part of the legs of the first one.
 */
const HEAD = { from: [8, 8, 8, 8], to: [4, 0] }
const BODY = { from: [20, 20, 8, 12], to: [4, 8] }
const ARMS = [{ from: [44, 20, 4, 12], to: [0, 8] }, { from: [44, 20, 4, 12], to: [12, 8], mirror: true }]
const LEGS = [{ from: [4, 20, 4, 12], to: [4, 20] }, { from: [4, 20, 4, 12], to: [8, 20], mirror: true }]

export const WORN = {
  head: { layer: 'humanoid', pieces: [HEAD] },
  chest: { layer: 'humanoid', pieces: [BODY, ...ARMS] },
  legs: { layer: 'humanoid_leggings', pieces: [BODY, ...LEGS] },
  feet: { layer: 'humanoid', pieces: LEGS },
}

/** The size of the figure in pixels of the game. */
export const FIGURE = { width: 16, height: 32 }

const bare = (id) => String(id ?? '').trim().toLowerCase().replace(/^minecraft:/, '')

/**
 * The textures that make up the armor, bottom first, as `{path, tint}` (a path of `textures/`, without the extension; a tint is a color to multiply the texture by).
 * [equipment] is the pack's or the game's `equipment/<id>.json` when there is one, which says the layers, and which of them are dyed: leather is dyed with [dye].
 */
export function armorLayers(slot, material, equipment, dye) {
  const { layer } = WORN[slot]
  const id = equipmentOf(material)
  const listed = equipment?.layers?.[layer]
  const entries = Array.isArray(listed) && listed.length ? listed : [{ texture: id }]
  return entries.filter((e) => typeof e.texture === 'string').map((entry) => {
    const name = entry.texture.replace(/^minecraft:/, '')
    const undyed = entry.dyeable ? (entry.dyeable.color_when_undyed ?? 0xa06540) & 0xffffff : null
    return { path: `entity/equipment/${layer}/${name}`, tint: entry.dyeable ? (typeof dye === 'number' ? dye : undyed) : null }
  })
}

/**
 * The texture of the trim over the armor: the pattern in the colors of the material. Armor of the material itself gets the darker shade of it, as in the game.
 * Null for a pattern or a material that is not named.
 */
export function trimPath(slot, material, trim) {
  const pattern = bare(trim?.pattern)
  const trimMaterial = bare(trim?.material)
  if (!pattern || !trimMaterial || !slot) return null
  const folder = WORN[slot].layer === 'humanoid_leggings' ? 'humanoid_leggings' : 'humanoid'
  const darker = equipmentOf(material) === trimMaterial ? '_darker' : ''
  return `trims/entity/${folder}/${pattern}_${trimMaterial}${darker}`
}

/**
 * The pixels of the worn armor. [textures] is a function from a path (see [armorLayers] and [trimPath]) to a [Texture] or null; [scale] is the pixels of the picture for
 * one pixel of the game. A texture that is missing is left out.
 * @returns {Texture}
 */
export function compose(slot, layers, trimTexture, textures, scale = 1) {
  const width = FIGURE.width * scale
  const height = FIGURE.height * scale
  const data = new Uint8ClampedArray(width * height * 4)
  const draw = (texture, tint) => {
    if (!texture) return
    // The textures are 64 wide for an armor, but a pack may ship a bigger one: the parts are read in proportion.
    const k = texture.width / 64
    for (const piece of WORN[slot].pieces) {
      const [sx, sy, w, h] = piece.from
      for (let y = 0; y < h * scale; y++) {
        for (let x = 0; x < w * scale; x++) {
          const u = piece.mirror ? w * scale - 1 - x : x
          const tx = Math.floor((sx + u / scale) * k)
          const ty = Math.floor((sy + y / scale) * k)
          const from = (ty * texture.width + tx) * 4
          const alpha = texture.data[from + 3] / 255
          if (alpha === 0) continue
          const to = (((piece.to[1] * scale + y) * width) + piece.to[0] * scale + x) * 4
          const rgb = [0, 1, 2].map((c) => (tint === null || tint === undefined ? texture.data[from + c] : Math.round(texture.data[from + c] * (((tint >> (16 - 8 * c)) & 255) / 255))))
          // The texture over what is there.
          const behind = data[to + 3] / 255
          const out = alpha + behind * (1 - alpha)
          for (let c = 0; c < 3; c++) data[to + c] = Math.round((rgb[c] * alpha + data[to + c] * behind * (1 - alpha)) / out)
          data[to + 3] = Math.round(out * 255)
        }
      }
    }
  }
  for (const layer of layers) draw(textures(layer.path), layer.tint)
  if (trimTexture) draw(textures(trimTexture), null)
  return new Texture(width, height, data)
}

/** Reads what [compose] needs through [assets] (see assets.js) and composes it, or null when there is no armor texture. */
export async function worn(assets, item, scale = 1) {
  const slot = slotOf(item?.material)
  if (!slot) return null
  const id = equipmentOf(item.material)
  const equipment = await assets.json('minecraft', 'equipment', id)
  const dye = typeof item.color === 'string' && /^#[0-9a-f]{6}$/i.test(item.color) ? parseInt(item.color.slice(1), 16) : undefined
  const layers = armorLayers(slot, item.material, equipment, dye)
  const trim = trimPath(slot, item.material, item.trim)
  const paths = [...layers.map((l) => l.path), ...(trim ? [trim] : [])]
  const loaded = new Map(await Promise.all(paths.map(async (path) => [path, await assets.texture('minecraft', path)])))
  if (!layers.some((l) => loaded.get(l.path))) return null
  return compose(slot, layers, trim, (path) => loaded.get(path) ?? null, scale)
}
