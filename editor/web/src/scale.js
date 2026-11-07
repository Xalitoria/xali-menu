// The GUI scale of the game, as the preview keeps it. The game draws each pixel of its pictures as a whole number of screen pixels (the GUI
// scale: 1, 2, 3...), so a picture is never smeared. The page has the same problem on a screen where one pixel of the page is not one pixel
// of the screen (a browser zoomed to 125 %, or a display with a pixel ratio of 2): three page pixels of 1.25 screen pixels each is 3.75, and
// every other pixel of the picture would be wider than its neighbors.
//
// So the preview works in screen pixels. A GUI scale of N at a pixel ratio of r makes each pixel of the game round(N * r) screen pixels, a
// whole number, and the picture is as large on the page as that many screen pixels are: round(N * r) / r page pixels. When the browser
// zooms, the ratio changes and the preview follows, still a whole number of screen pixels per pixel of the game, while the rest of the page
// scales as the browser makes it.

/** Screen pixels to a pixel of the game, for GUI scale [gui] at pixel ratio [ratio]. */
export function deviceScale(gui, ratio = 1) {
  return Math.max(1, Math.round(gui * ratio))
}

/** The GUI scales the preview offers. */
export const SCALES = [1, 2, 3, 4, 5, 6]

/**
 * The biggest GUI scale at which [width] pixels of the game fit in [available] page pixels; 1 if even that does not.
 * @param {number} width the width of the window in pixels of the game
 * @param {number} available page pixels
 * @param {number} ratio the pixel ratio of the page
 */
export function fitScale(width, available, ratio = 1) {
  for (const gui of [...SCALES].reverse()) if ((width * deviceScale(gui, ratio)) / ratio <= available) return gui
  return 1
}

/** What the drawing code needs to know about one scale, made once for a draw. */
export function viewOf(gui, ratio = (typeof window === 'undefined' ? 1 : window.devicePixelRatio || 1)) {
  const device = deviceScale(gui, ratio)
  return {
    gui,
    ratio,
    /** screen pixels to a pixel of the game */
    device,
    /** page pixels for [game] pixels of the game */
    css: (game) => (game * device) / ratio,
  }
}
