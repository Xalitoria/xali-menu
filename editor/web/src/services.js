// The things that draw with the files of the game, made together so that they all see the same resource pack: when the pack changes,
// everything is made again.

import { Assets } from './assets.js'
import { Text } from './text.js'
import { WindowView } from './window.js'
import { ItemRenderer } from './itemrender.js'
import { settings as iconSettings } from './icons.js'

export const services = {
  assets: null, text: null, window: null, renderer: null, pack: null,
  /** Screen pixels to a pixel of the game (the GUI scale), or 0 to fit the window to the space there is. */
  scale: 0,
  /** Pixels of the game left around the window, for glyphs that reach past it. */
  room: 64,
}

/** Starts over with [pack] (or none) in front of the game's own files. */
export function configure(pack = null) {
  services.pack = pack
  services.assets = new Assets({ pack })
  services.text = new Text(services.assets, () => services.pack)
  services.window = new WindowView(services.text, services.assets)
  services.renderer = new ItemRenderer(services.assets)
  iconSettings.pack = pack
  iconSettings.renderer = services.renderer
}

configure(null)
