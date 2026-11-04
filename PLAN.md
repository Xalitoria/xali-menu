# XaliMenu plan

Goal: meet, and where it is cheap to, exceed [AbstractMenus](/mount/storage/Code/java/abstractmenus) (AM). This file
replaces the earlier plan, which was lost when the workspace was split into one repository per module. It was rebuilt on
2026-10-31 from a comparison of the two code bases.

XaliMenu keeps its own schema (kebab-case HOCON through kotlinx.serialization) and its addon API, which has no Kotlin
types in it. It does not aim for file compatibility with AM. It aims for the same abilities.

## Where AM stands, and where XaliMenu does

| Area | AM | XaliMenu now |
|---|---|---|
| Menu kinds | simple, animated, generated (paged) | step 3: simple, animated, paged (with layouts) |
| Paging | `GeneratedMenu`, catalogs, `pageNext` / `pagePrev` | step 1, below |
| Activators | ~18 (command, click item / block / entity / NPC, join, chat, region, sign, lever, button, plate, swap items) | step 2: commands, item, join, chat, swap-hands, block (sign, lever, button, plate), entity |
| Actions | ~67 | 41 |
| Rules | ~30 | 26 |
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

### 1. Paging and catalogs (done, tested live)

- `pages` in a menu: a catalog, the slots it fills, an item template, optional sort and an `empty` item.
- Catalogs: `players`, `worlds`, `values`, `range`, and `XaliMenuApi.registerCatalog` for addons.
- `{page}`, `{pages}`, `{total}`, `{page_next}`, `{page_prev}` in any text of the menu, `{index}` and the catalog's own
  fields in the template; `%xalimenu_page%` and the like for other plugins.
- The `page` action (`by`, `to`), `MenuContext.pageBy` / `pageTo`.
- Not in this step: the `entities` catalog (needs an entity scope that is safe on Folia), and a catalog of items.
- Titles update while the menu is open (`{page}` and placeholders), through the deprecated but working
  `InventoryView.setTitle`, which resends the window with the new title. Sent only when the text changes.
  Titles are the usual component (color library, then MiniMessage) serialized to legacy text with hex kept.
- Tested live on the dev server's Folia 26.2 with a mineflayer bot (see below): page contents, `{page}` on items,
  the title following the page, element-bound clicks, `page` by / to last, and the join activator.
- Covered by unit tests: the layout (`layoutPage`), the catalogs, the context scope, the `page` action, reading the menu
  and external catalogs.

### 2. Activators (done, tested live)

- `open-with` in a menu: `item`, `join`, `chat`, `swap-hands`, `block` and `entity`, each with a `require` gate, `cancel`
  and `args` that can use the event's values (`{message}`, `{block}`, ...).
- One `block` type covers AM's click-block, sign, lever, button and plate: keys or `#tags`, a position, and `PHYSICAL`
  for stepping on a block.
- Matching is pure functions (`Activators.kt`) covered by unit tests. Live, with the bot: `chat` (exact, ignoring case,
  canceled, and other messages left alone), `item` (right click opens, a swing does not), `swap-hands`, `join` and
  `block` (a button at a position). Not tried live: `entity`, and `sneaking` / `require` gates.
- `npc`: a click on an NPC of FancyNpcs (soft dependency, its event is read by reflection so nothing links to its API), by
  name or id, `LEFT` / `RIGHT` / `ANY`, with `cancel` stopping the NPC's own actions. Citizens is not supported on purpose.
  Tested live (`live/bot/npc.js`, `XM_PLUGINS=FancyNpcs`): right click opens, an unmatched left click does not, a
  sneaking left click opens with `{npc_id}`.
- `region-enter` / `region-leave` (WorldGuard, compiled against its API as `compileOnly`, a soft dependency): from the
  move and teleport events, once a block is crossed, comparing the regions at the block with those the player stood in
  (`RegionChange`, tested); the menu opens two ticks later, since a teleport closes what is open. Joining inside a region is
  silent, a change of world leaves all and enters all. A `region` condition too. Tested live on Paper with FAWE and
  WorldGuard 7.0.19 (`live/bot/region.js`): entering, moving inside (silent), leaving, and the condition inside and out.
- An addon API for activators (`registerActivator`) is open: activators are event-driven, so it needs a design of its own.

### 3. Menu kinds (done, tested live)

- **Animations**: `animation { loop, on-start, on-end, frames }`, each frame with `delay`, `clear`, `require`, `on-start`,
  `on-end` and `items`. The frames are an overlay over the menu (`MenuView.overlay`) drawn first in `draw()`, and each frame
  schedules the next on the player's region, so there is no polling loop. `FrameCursor` is the pure part and is tested.
- **Layouts** for paged menus: `pages.layout` and `pages.cell` as an alternative to `slots`.
- **Nested menus**: an element's values reach the template's `open` action as `{name}`, so no new code was needed; it
  is documented and tested.
- Tested live with the bot: frame timing (500 ms steps to within a tick), additive and clearing frames, the base item
  coming back, `on-start` / `on-end`, a layout filling its cells over two pages, and an element opening a nested menu.
- **Layout for any menu** (done after the seven steps): `layout` (rows of nine characters) and `legend` (character to item
  name) put each item in every slot where its character stands, besides its own `slot` / `slots` (`Menu.placed()`). Tested
  by unit tests and live (`live/bot/layout.js`).

### 4. Actions (done, tested live)

- **Items**: `give-item` (any item a menu can show, with `overflow` drop or abort), `take-item` (aborts when there are too few,
  so it can stand before what is given for them), `clear-items`. Matching is by material, plain name and custom model.
- **The viewer**: `potion` (add, remove, clear all), `teleport` (`~` relative coordinates, other worlds, on the async
  teleport that is safe on Folia), `gamemode`, `health` and `food` (`SET` / `ADD` / `TAKE`, held in range), `level`.
- **Showing things**: `title`, `boss-bar` (hidden after `duration`), `book`, `particle`.
- **Asking**: `input`, a chat prompt that closes the menu, keeps the reply out of chat, validates it with a regex and runs
  `then` with `{input}`; `cancel` word, `timeout`, `on-cancel`.
- **Flow**: `all`, `random` (pick `count`), `run-for` (an action list for each element of a catalog, with `{index}`), `log`.
- **Permissions**: `group` (Vault) and `meta` (LuckPerms, through its command).
- Unit tests: removals across stacks, range and coordinate helpers, prompt verdicts, quoting for the console, flow actions,
  and reading and writing every new action. Live (Folia 26.2, bot): give / take with name match and the abort when there is
  nothing to take, clear, potion packet, teleport relative on x and z, game mode, health and food, levels, title packets,
  boss bar shown and hidden, book packet, particle packet and a bad particle failing alone, `run-for` and `random`, the
  `input` prompt (invalid reply, valid reply, timeout, cancel word) and ordinary chat afterwards.
- Not tested live: `group` (needs Vault's permission provider) and `meta` (needs LuckPerms).
- `skin` (a player's name or a link, `slim` for a picture) and `skin-reset` through SkinsRestorer: looked up off the region
  threads, stored for the next visit, put on on the player's thread; one that can't be found is a console warning. Tested
  live (`live/bot/skin.js`): the stored skin after the set (Notch's id), after the reset (gone), and a name that doesn't exist.
- Not done: `place-item` and the
  other per-slot item actions of AM (`button-set`, `item-refresh`, ...), whose job `view` gates and `update` already do, and
  recipes (`recipe-add`).

### 5. Conditions (done, tested live)

- Parity: `free-slots`, `held-item`, `group` (Vault), `gamemode`, `health`, `food`, `level`, `experience`, `world`, `online`,
  `online-count`, `variable-exists`, `and`, `or`, `one-of`, `at-least`, `chance`. The ranges take `min` / `max` as numbers or
  placeholders. The DeluxeMenus importer maps `has exp` (points or `level: true`).
- Beyond: `cooldown` and a `cooldown` action. A condition only looks, so starting the cooldown is an action; the state is the
  variable `cooldown_<key>` with an expiry, so it is kept wherever the variables are (shared across servers with the right
  rule), and a store that has not loaded counts as running. `once` is a cooldown with no duration.
- Unit tests: the combinations and that they stop as soon as the answer is known, the ranges and their placeholders,
  settings that are refused, reading and writing every new condition. Live (bot, items shown or hidden by `view`): free
  slots with and without items, held item, game mode, health, food, level, experience on both sides of its limit, worlds,
  online, the four combinations, `chance` at both ends, a cooldown ending and a once-only one staying, and `variable-exists`.
- Not tested live: `group` (needs a Vault permission provider). Not done: JavaScript.

### 6. Item properties (done, tested live)

- New: `item-name`, `damage`, `rarity`, `glow`, `max-stack-size`, `hide-tooltip`, `item-model`, `food`, `stored-enchants`,
  `potion-type`, `trim`, `book`, `firework` (rocket and star), `fish`. Paper's item meta where it covers a property and data
  components for the rest (food, tooltip display). Shulker color is the material. Skull owner and texture were already the
  `head:` and `texture:` materials.
- A value that can't be used is reported when the menu is read (`validate`). An item that throws while it is built used to
  take the whole menu's drawing down (found by a deliberately bad item, `damage = -3`); it is now a barrier, noted once, and
  the values that Paper would refuse are held in range.
- The DeluxeMenus importer maps `damage`, `rarity`, `hide_tooltip`, `enchantment_glint_override`, `trim_pattern` and
  `trim_material` and `item_model`. The names were checked against the DeluxeMenus 1.14.1 jar (the option names it reads, and
  its six bundled example menus import with no warning but its own stray `sweet: true`). It has no `open_with_item`. It also
  reads `light_level`, `tooltip_style`, `lore_append_mode` and `model_data_component`, which are warned about and ignored.
- Checked against the running plugin (DeluxeMenus 1.14.1 on Paper 26.2, `live/bot/compare.js`: both open the same menu, the
  windows and the chat from clicks are compared). That found and fixed: `data` is the `damage` of a current material (was
  turned into a variant); `minimum_requirements` leaves non-optional requirements mandatory (a gate's `minimum` did the
  opposite, now it follows DeluxeMenus); the requirement aliases (`item`, `money`, `perm`, `contains`, `equals`, `regex`, the
  long comparison names, `does not have item`), `has permissions` and `string length` were missing; `[jsonbroadcast]`; and an
  `amount` above the stack size of the material was cut (up to 99 now). The harness was also using stale util / color jars.
  Not carried over: `[meta]` and the `has meta` requirement (DeluxeMenus' persistent meta; the variables are the nearest
  thing, but the placeholders would have to be rewritten), `is near`, `javascript`, `is object`, `[placeholder]`.
- Tested live: the player data of a bot that was given every kind of item shows each component with the value written
  (the server has no `/data` for entities on Folia, so the saved data was read instead).
- `attributes` (attack damage, max health and the like, per slot group), `tooltip-style` and `equippable` (slot, model, camera
  overlay, swappable, dispensable, damage on hurt). Bad names are reported by `validate`; tested live (the saved data of a
  player holds each component as written, and `validate` lists the wrong ones).
- Not done: the tool component (rules and speeds).

### 7. Housekeeping (done, tested live)

- `/xmenu reload <menu>` (one file; a file that cannot be read leaves the old menu in place), `/xmenu info <menu>`,
  `/xmenu validate [menu]` (every problem of every menu and every unreadable file, without opening any), and menu names
  complete in all of them.
- `/xmenu inspect [player]`, the debug view: per slot, the items that claim it, which one is drawn, and each check of the
  `view` gate with its result (`Gate.explain`). The first lines go to chat, all of them to the console.
- The bot-driven live test is in the repository: `menu/live` (`setup.sh`, `server.sh`, `bot/*.js`, the menus they use and a
  README). Tried from scratch in a fresh folder: it built, set up, started a server and ran the action and condition runs.
- Messages for these are in the language file (`commands.tools`), written back on the first start.
- Not done: tab completion for the words of a menu's own `args`.
