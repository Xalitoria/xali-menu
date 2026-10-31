# XaliMenu plan

Goal: meet, and where it is cheap to, exceed [AbstractMenus](/mount/storage/Code/java/abstractmenus) (AM). This file
replaces the earlier plan, which was lost when the workspace was split into one repository per module. It was rebuilt on
2026-10-31 from a comparison of the two code bases.

XaliMenu keeps its own schema (kebab-case HOCON through kotlinx.serialization) and its addon API, which has no Kotlin
types in it. It does not aim for file compatibility with AM. It aims for the same abilities.

## Where AM stands, and where XaliMenu does

| Area | AM | XaliMenu now |
|---|---|---|
| Menu kinds | simple, animated, generated (paged) | simple |
| Paging | `GeneratedMenu`, catalogs, `pageNext` / `pagePrev` | step 1, below |
| Activators | ~18 (command, click item / block / entity / NPC, join, chat, region, sign, lever, button, plate, swap items) | commands only |
| Actions | ~67 | 19 |
| Rules | ~30 | 8 |
| Item properties | ~34 | the common ones |
| Variables | SQLite | memory, SQLite and MySQL, with sync. **Ahead.** |
| Addon API | `Types` registry, Java and Kotlin types | actions, conditions, item sources, no Kotlin types. **Ahead.** |
| Importer | none | DeluxeMenus. **Ahead.** |
| Folia | FoliaLib | region schedulers throughout |

AM weaknesses that XaliMenu avoids on purpose:

- Paging counts elements before it checks each item's rules, so a page can come out short. XaliMenu filters first.
- The element being drawn is global state on the menu (`currentObject`), set again on a click. XaliMenu binds it to the
  context of each slot.
- The catalogs are a closed set. XaliMenu's can be added to by other plugins.

## Steps

Each step ends with tests, the README section, and a commit.

### 1. Paging and catalogs (done, not yet seen on a live server)

- `pages` in a menu: a catalog, the slots it fills, an item template, optional sort and an `empty` item.
- Catalogs: `players`, `worlds`, `values`, `range`, and `XaliMenuApi.registerCatalog` for addons.
- `{page}`, `{pages}`, `{total}`, `{page_next}`, `{page_prev}` in any text of the menu, `{index}` and the catalog's own
  fields in the template; `%xalimenu_page%` and the like for other plugins.
- The `page` action (`by`, `to`), `MenuContext.pageBy` / `pageTo`.
- Not in this step: the `entities` catalog (needs an entity scope that is safe on Folia), and a catalog of items.
- Titles update while the menu is open (`{page}` and placeholders), through the deprecated but working
  `InventoryView.setTitle`, which resends the window with the new title. Sent only when the text changes.
  Titles are the usual component (color library, then MiniMessage) serialized to legacy text with hex kept.
- Covered by unit tests: the layout (`layoutPage`), the catalogs, the context scope, the `page` action, reading the menu
  and external catalogs. **Not covered:** `MenuView.draw` and the click path against a real inventory. The dev server's
  Folia 26.2 cannot be joined by mineflayer (it stops at 26.1), so that needs a real client or ViaVersion.

### 2. Activators

Menus open only by command today. Add an `open-with` section to a menu: `item` (right or left click with an item, by
material, name or model), `join`, `chat` (exact or contains), `swap-hands`, `click-block` (by type or location),
`click-entity` (by type or name), `region-enter` / `region-leave` (WorldGuard, soft), `sign`, plus the Citizens NPC
click (soft). Every activator can carry a `require` gate and `actions`, and cancels the original event if asked to.

### 3. Menu kinds

- **Animated**: frames with an item set and a duration, `loop`, per-frame actions. Built on the view's draw, with one
  repeating task per open view (as `refresh` already is).
- **Matrix layouts** for paged menus (`layout = ["#########", "#.......#", ...]`) as an alternative to `slots`.
- **Nested pages**: a catalog element can open another menu with the element as its argument.

### 4. Actions

Parity first, then beyond: item give / take / clear, potion effects, teleport, game mode, health, food, title and
subtitle, boss bar, book, particle, `input` (chat prompt that fills a variable), bulk and random action lists, `place-item`,
LuckPerms group and meta (soft), skin set / reset (soft), `log`, and `run-for` (an action list for every element of a
catalog).

### 5. Conditions

Parity: free slot, held item, group, game mode, health, food, level, world, online, region (soft), `and`, `or`, `one-of`,
`chance`, variable exists. Beyond: `cooldown` (per player and per key, kept in the variable store) and `once`.

### 6. Item properties

Skull owner and texture variants, book, firework, shulker color, tropical fish, armor trims, `damage`, `custom-name`,
`rarity`, glow, `max-stack-size`, food and tool components. Where Paper's data components cover it, use them rather than
NBT.

### 7. Housekeeping

- `/xmenu reload <menu>` for one menu, `/xmenu info <menu>`, tab completion of menu names everywhere.
- `/xmenu validate`, which prints the problems of every menu without opening any.
- A debug view that shows why an item was or wasn't drawn.
- Tests against a live Folia build in the dev server for the parts the unit tests cannot reach.
